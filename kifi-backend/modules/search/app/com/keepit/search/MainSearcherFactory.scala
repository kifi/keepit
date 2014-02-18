package com.keepit.search

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
import com.keepit.search.sharding.ShardedURIGraphIndexer
import com.keepit.search.graph.bookmark.URIGraphIndexer
import com.keepit.search.graph.bookmark.URIGraphSearcher
import com.keepit.search.graph.bookmark.URIGraphSearcherWithUser
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.graph.collection.CollectionIndexer
import com.keepit.search.graph.collection.CollectionSearcher
import com.keepit.search.sharding._
import com.keepit.search.graph.user.UserGraphsCommander

@Singleton
class MainSearcherFactory @Inject() (
    shardedArticleIndexer: ShardedArticleIndexer,
    userIndexer: UserIndexer,
    userGraphsCommander: UserGraphsCommander,
    shardedUriGraphIndexer: ShardedURIGraphIndexer,
    shardedCollectionIndexer: ShardedCollectionIndexer,
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

  private[this] val consolidateURIGraphSearcherReq = new RequestConsolidator[(Shard[NormalizedURI],Id[User]), URIGraphSearcherWithUser](3 seconds)
  private[this] val consolidateCollectionSearcherReq = new RequestConsolidator[(Shard[NormalizedURI], Id[User]), CollectionSearcherWithUser](3 seconds)
  private[this] val consolidateBrowsingHistoryReq = new RequestConsolidator[Id[User], MultiHashFilter[BrowsedURI]](10 seconds)
  private[this] val consolidateClickHistoryReq = new RequestConsolidator[Id[User], MultiHashFilter[ClickedURI]](3 seconds)

  def apply(
    shard: Shard[NormalizedURI],
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

    val socialGraphInfo = getSocialGraphInfo(shard, userId, filter)

    new MainSearcher(
        userId,
        queryString,
        lang,
        numHitsToReturn,
        filter,
        config,
        articleSearcher,
        parserFactory,
        socialGraphInfo,
        clickBoostsFuture,
        browsingHistoryFuture,
        clickHistoryFuture,
        spellCorrector,
        monitoredAwait,
        airbrake
    )
  }

  def warmUp(userId: Id[User], logging: Boolean = true): Seq[Future[Any]] = {
    val browsingHistoryFuture = getBrowsingHistoryFuture(userId)
    val clickHistoryFuture = getClickHistoryFuture(userId)

    // logging after firing futures
    if (logging) {
      log.info(s"warming up $userId")
      Statsd.increment(s"warmup.$userId")
    }

    Seq(browsingHistoryFuture, clickHistoryFuture) // returning futures to pin them in the heap
  }

  def clear(): Unit = {
    consolidateURIGraphSearcherReq.clear()
    consolidateCollectionSearcherReq.clear()
    userGraphsCommander.clear()
  }

  def getUserSearcher = new UserSearcher(userIndexer.getSearcher)

  def getSocialGraphInfo(shard: Shard[NormalizedURI], userId: Id[User], filter: SearchFilter): SocialGraphInfo = {
    new SocialGraphInfo(userId, getURIGraphSearcher(shard, userId), getCollectionSearcher(shard, userId), filter: SearchFilter, monitoredAwait)
  }

  private[this] def getURIGraphSearcherFuture(shard: Shard[NormalizedURI], userId: Id[User]) = consolidateURIGraphSearcherReq((shard, userId)){ case (shard, userId) =>
    val uriGraphIndexer = shardedUriGraphIndexer.getIndexer(shard)
    Promise[URIGraphSearcherWithUser].success(URIGraphSearcher(userId, uriGraphIndexer, userGraphsCommander)).future
  }

  def getURIGraphSearcher(shard: Shard[NormalizedURI], userId: Id[User]): URIGraphSearcherWithUser = {
    Await.result(getURIGraphSearcherFuture(shard, userId), 5 seconds)
  }

  private[this] def getCollectionSearcherFuture(shard: Shard[NormalizedURI], userId: Id[User]) = consolidateCollectionSearcherReq((shard, userId)){ case (shard, userId) =>
    Promise[CollectionSearcherWithUser].success(CollectionSearcher(userId, shardedCollectionIndexer.getIndexer(shard))).future
  }

  def getCollectionSearcher(shard: Shard[NormalizedURI], userId: Id[User]): CollectionSearcherWithUser = {
    Await.result(getCollectionSearcherFuture(shard, userId), 5 seconds)
  }

  private[this] def getBrowsingHistoryFuture(userId: Id[User]) = consolidateBrowsingHistoryReq(userId){ userId =>
    SafeFuture(browsingHistoryTracker.getMultiHashFilter(userId))
  }

  private[this] def getClickHistoryFuture(userId: Id[User]) = consolidateClickHistoryReq(userId){ userId =>
    SafeFuture(clickHistoryTracker.getMultiHashFilter(userId))
  }

  private[this] def getClickBoostsFuture(userId: Id[User], queryString: String, maxResultClickBoost: Float, useS3FlowerFilter: Boolean) = {
    resultClickTracker.getBoostsFuture(userId, queryString, maxResultClickBoost, useS3FlowerFilter)
  }

  def bookmarkSearcher(shard: Shard[NormalizedURI], userId: Id[User]) = {
    val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
    val uriGraphIndexer = shardedUriGraphIndexer.getIndexer(shard)
    val uriGraphSearcher = URIGraphSearcher(userId, uriGraphIndexer, userGraphsCommander)
    new BookmarkSearcher(userId, articleSearcher, uriGraphSearcher)
  }

  def semanticVectorSearcher(shard: Shard[NormalizedURI]) = {
    val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher
    new SemanticVectorSearcher(articleSearcher)
  }
}
