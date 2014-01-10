package com.keepit.search

import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.bookmark.URIGraphSearcherWithUser
import com.keepit.search.article.ArticleIndexer
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.model._
import com.google.inject.{Inject, Singleton}
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserSearcher
import com.keepit.search.query.parser.MainQueryParserFactory
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.statsd.api.Statsd
import com.keepit.search.semantic.SemanticVectorSearcher
import com.keepit.search.tracker.BrowsingHistoryTracker
import com.keepit.search.tracker.BrowsedURI
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ShardedArticleIndexer

@Singleton
class MainSearcherFactory @Inject() (
    shardedArticleIndexer: ShardedArticleIndexer,
    userIndexer: UserIndexer,
    uriGraph: URIGraph,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    clickHistoryTracker: ClickHistoryTracker,
    shoeboxClient: ShoeboxServiceClient,
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait,
    airbrake: AirbrakeNotifier,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices
 ) extends Logging {

  private[this] val consolidateURIGraphSearcherReq = new RequestConsolidator[Id[User], URIGraphSearcherWithUser](3 seconds)
  private[this] val consolidateCollectionSearcherReq = new RequestConsolidator[Id[User], CollectionSearcherWithUser](3 seconds)
  private[this] val consolidateBrowsingHistoryReq = new RequestConsolidator[Id[User], MultiHashFilter[BrowsedURI]](10 seconds)
  private[this] val consolidateClickHistoryReq = new RequestConsolidator[Id[User], MultiHashFilter[ClickedURI]](3 seconds)

  def apply(
    shard: Shard,
    userId: Id[User],
    queryString: String,
    lang: Lang,
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig
  ) = {
    val clickBoostsFuture = getClickBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"), config.asBoolean("useS3FlowerFilter"))
    val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
    val browsingHistoryFuture = getBrowsingHistoryFuture(userId)
    val clickHistoryFuture = getClickHistoryFuture(userId)

    val socialGraphInfoFuture = getSocialGraphInfoFuture(userId, filter)

    new MainSearcher(
        userId,
        queryString,
        lang,
        numHitsToReturn,
        filter,
        config,
        articleSearcher,
        parserFactory,
        socialGraphInfoFuture,
        getCollectionSearcher(userId),
        clickBoostsFuture,
        browsingHistoryFuture,
        clickHistoryFuture,
        shoeboxClient,
        spellCorrector,
        monitoredAwait,
        airbrake
    )
  }

  def warmUp(userId: Id[User]): Seq[Future[Any]] = {
    log.info(s"warming up $userId")
    Statsd.increment(s"warmup.$userId")
    val searchFriendsFuture = shoeboxClient.getSearchFriends(userId)
    val friendsFuture = shoeboxClient.getFriends(userId)
    val browsingHistoryFuture = getBrowsingHistoryFuture(userId)
    val clickHistoryFuture = getClickHistoryFuture(userId)

    Seq(searchFriendsFuture, friendsFuture, browsingHistoryFuture, clickHistoryFuture) // returning futures to pin them in the heap
  }

  def clear(): Unit = {
    consolidateURIGraphSearcherReq.clear()
    consolidateCollectionSearcherReq.clear()
  }

  def getUserSearcher = new UserSearcher(userIndexer.getSearcher)

  def getSocialGraphInfoFuture(userId: Id[User], filter: SearchFilter): Future[SocialGraphInfo] = {
    SafeFuture {
      new SocialGraphInfo(userId, getURIGraphSearcher(userId), getCollectionSearcher(userId), filter: SearchFilter)
    }
  }

  private[this] def getURIGraphSearcherFuture(userId: Id[User]) = consolidateURIGraphSearcherReq(userId){ userId =>
    Promise[URIGraphSearcherWithUser].success(uriGraph.getURIGraphSearcher(userId)).future
  }

  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser = {
    Await.result(getURIGraphSearcherFuture(userId), 5 seconds)
  }

  private[this] def getCollectionSearcherFuture(userId: Id[User]) = consolidateCollectionSearcherReq(userId){ userId =>
    Promise[CollectionSearcherWithUser].success(uriGraph.getCollectionSearcher(userId)).future
  }

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    Await.result(getCollectionSearcherFuture(userId), 5 seconds)
  }

  private[this] def getBrowsingHistoryFuture(userId: Id[User]) = consolidateBrowsingHistoryReq(userId){ userId =>
    SafeFuture(browsingHistoryTracker.getMultiHashFilter(userId))
  }

  private[this] def getClickHistoryFuture(userId: Id[User]) = consolidateClickHistoryReq(userId){ userId =>
    SafeFuture(clickHistoryTracker.getMultiHashFilter(userId))
  }

  private[this] def getClickBoostsFuture(userId: Id[User], queryString: String, maxResultClickBoost: Float, useS3FlowerFilter: Boolean) = {
    future {
      resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost, useS3FlowerFilter)
    }
  }

  def bookmarkSearcher(shard: Shard, userId: Id[User]) = {
    val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher(userId)
    new BookmarkSearcher(userId, articleSearcher, uriGraphSearcher)
  }

  def semanticVectorSearcher(shard: Shard) = {
    val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher()
    new SemanticVectorSearcher(articleSearcher, uriGraphSearcher)
  }
}
