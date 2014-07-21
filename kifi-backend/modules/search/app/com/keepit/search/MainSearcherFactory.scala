package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserSearcher
import com.keepit.search.query.parser.MainQueryParserFactory
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker
import com.keepit.search.graph.bookmark.URIGraphSearcher
import com.keepit.search.graph.bookmark.URIGraphSearcherWithUser
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.graph.collection.CollectionSearcher
import com.keepit.search.graph.user.UserGraphsSearcherFactory
import com.keepit.search.sharding._
import com.keepit.search.query.HotDocSetFilter
import com.keepit.search.spellcheck.SpellCorrector

@Singleton
class MainSearcherFactory @Inject() (
    shardedArticleIndexer: ShardedArticleIndexer,
    userIndexer: UserIndexer,
    userGraphsSearcherFactory: UserGraphsSearcherFactory,
    shardedUriGraphIndexer: ShardedURIGraphIndexer,
    shardedCollectionIndexer: ShardedCollectionIndexer,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    clickHistoryTracker: ClickHistoryTracker,
    searchConfigManager: SearchConfigManager,
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices) extends Logging {

  private[this] val consolidateURIGraphSearcherReq = new RequestConsolidator[(Shard[NormalizedURI], Id[User]), URIGraphSearcherWithUser](3 seconds)
  private[this] val consolidateCollectionSearcherReq = new RequestConsolidator[(Shard[NormalizedURI], Id[User]), CollectionSearcherWithUser](3 seconds)
  private[this] val consolidateClickHistoryReq = new RequestConsolidator[Id[User], MultiHashFilter[ClickedURI]](10 seconds)
  private[this] val consolidateLangFreqsReq = new RequestConsolidator[Id[User], Map[Lang, Int]](180 seconds)
  private[this] val consolidateConfigReq = new RequestConsolidator[(Id[User]), (SearchConfig, Option[Id[SearchConfigExperiment]])](10 seconds)

  lazy val searchServiceStartedAt: Long = fortyTwoServices.started.getMillis()

  def apply(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): Seq[MainSearcher] = {
    val clickHistoryFuture = getClickHistoryFuture(userId)
    val clickBoostsFuture = getClickBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"))

    val parser = parserFactory(lang1, lang2, config)

    val searchers = shards.toSeq.map { shard =>
      val socialGraphInfo = getSocialGraphInfo(shard, userId, filter)
      val articleSearcher = shardedArticleIndexer.getIndexer(shard).getSearcher

      new MainSearcher(
        userId,
        lang1,
        lang2,
        numHitsToReturn,
        filter,
        config,
        parser,
        articleSearcher,
        socialGraphInfo,
        clickBoostsFuture,
        clickHistoryFuture,
        monitoredAwait
      )
    }

    val hotDocs = new HotDocSetFilter(userId, clickBoostsFuture, monitoredAwait)
    parser.setPercentMatch(config.asFloat("percentMatch"))
    parser.setPercentMatchForHotDocs(config.asFloat("percentMatchForHotDocs"), hotDocs)
    parser.parse(queryString, searchers.map(_.collectionSearcher))

    searchers
  }

  def apply(
    shard: Shard[NormalizedURI],
    userId: Id[User],
    queryString: String,
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig): MainSearcher = {
    val searchers = apply(Set(shard), userId, queryString, lang1, lang2, numHitsToReturn, filter, config)
    searchers(0)
  }

  def warmUp(userId: Id[User]): Seq[Future[Any]] = {
    val clickHistoryFuture = getClickHistoryFuture(userId)

    Seq(clickHistoryFuture) // returning futures to pin them in the heap
  }

  def clear(): Unit = {
    consolidateURIGraphSearcherReq.clear()
    consolidateCollectionSearcherReq.clear()
    userGraphsSearcherFactory.clear()
  }

  def getUserSearcher = new UserSearcher(userIndexer.getSearcher)

  def getSocialGraphInfo(shard: Shard[NormalizedURI], userId: Id[User], filter: SearchFilter): SocialGraphInfo = {
    new SocialGraphInfo(userId, getURIGraphSearcher(shard, userId), getCollectionSearcher(shard, userId), filter: SearchFilter, monitoredAwait)
  }

  private[this] def getURIGraphSearcherFuture(shard: Shard[NormalizedURI], userId: Id[User]) = consolidateURIGraphSearcherReq((shard, userId)) {
    case (shard, userId) =>
      val uriGraphIndexer = shardedUriGraphIndexer.getIndexer(shard)
      val userGraphsSearcher = userGraphsSearcherFactory(userId)
      Promise[URIGraphSearcherWithUser].success(URIGraphSearcher(userId, uriGraphIndexer, userGraphsSearcher)).future
  }

  def getURIGraphSearcher(shard: Shard[NormalizedURI], userId: Id[User]): URIGraphSearcherWithUser = {
    Await.result(getURIGraphSearcherFuture(shard, userId), 5 seconds)
  }

  private[this] def getCollectionSearcherFuture(shard: Shard[NormalizedURI], userId: Id[User]) = consolidateCollectionSearcherReq((shard, userId)) {
    case (shard, userId) =>
      Promise[CollectionSearcherWithUser].success(CollectionSearcher(userId, shardedCollectionIndexer.getIndexer(shard))).future
  }

  def getCollectionSearcher(shard: Shard[NormalizedURI], userId: Id[User]): CollectionSearcherWithUser = {
    Await.result(getCollectionSearcherFuture(shard, userId), 5 seconds)
  }

  private[this] def getClickHistoryFuture(userId: Id[User]) = consolidateClickHistoryReq(userId) { userId =>
    SafeFuture(clickHistoryTracker.getMultiHashFilter(userId))
  }

  private[this] def getClickBoostsFuture(userId: Id[User], queryString: String, maxResultClickBoost: Float) = {
    resultClickTracker.getBoostsFuture(userId, queryString, maxResultClickBoost)
  }

  def distLangFreqsFuture(shards: Set[Shard[NormalizedURI]], userId: Id[User]): Future[Map[Lang, Int]] = consolidateLangFreqsReq(userId) {
    case userId =>
      Future.traverse(shards) { shard =>
        SafeFuture {
          val searcher = getURIGraphSearcher(shard, userId)
          searcher.getLangProfile()
        }
      }.map { results =>
        results.map(_.iterator).flatten.foldLeft(Map[Lang, Int]()) {
          case (m, (lang, count)) =>
            m + (lang -> (count + m.getOrElse(lang, 0)))
        }
      }
  }

  def getConfigFuture(userId: Id[User], experiments: Set[ExperimentType], predefinedConfig: Option[SearchConfig] = None): Future[(SearchConfig, Option[Id[SearchConfigExperiment]])] = {
    predefinedConfig match {
      case None =>
        consolidateConfigReq(userId) { k => searchConfigManager.getConfigFuture(userId, experiments) }
      case Some(conf) =>
        val default = searchConfigManager.defaultConfig
        // almost complete overwrite. But when search config parameter list changes, this prevents exception
        Future.successful((new SearchConfig(default.params ++ conf.params), None))
    }
  }

  def getSpellCorrector(): SpellCorrector = spellCorrector
}
