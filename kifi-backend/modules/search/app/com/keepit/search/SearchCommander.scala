package com.keepit.search

import com.keepit.search.engine.{ KifiSearch, DebugOption, SearchFactory }
import com.keepit.search.engine.result.{ KifiPlainResult, KifiShardResultMerger, KifiShardResult }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.Try
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.sharding.{ Sharding, Shard, ActiveShards }
import com.keepit.search.result._
import org.apache.lucene.search.{ Explanation, Query }
import com.keepit.search.index.DefaultAnalyzer
import scala.collection.mutable.ListBuffer

@ImplementedBy(classOf[SearchCommanderImpl])
trait SearchCommander {
  def search(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false): DecoratedResult

  def distSearch(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig],
    debug: Option[String]): PartialSearchResult

  def search2(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    libraryContextFuture: Future[LibraryContext],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): Future[KifiPlainResult]

  def distSearch2(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    libraryContext: LibraryContext,
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig],
    debug: Option[String]): KifiShardResult

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User]): Map[Lang, Int]

  def explain(
    userId: Id[User],
    uriId: Id[NormalizedURI],
    lang: Option[String],
    experiments: Set[ExperimentType],
    query: String): Option[(Query, Explanation)]

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[SharingUserInfo]

  def warmUp(userId: Id[User]): Unit
}

class SearchCommanderImpl @Inject() (
    shards: ActiveShards,
    searchFactory: SearchFactory,
    languageCommander: LanguageCommander,
    mainSearcherFactory: MainSearcherFactory,
    articleSearchResultStore: ArticleSearchResultStore,
    compatibilitySupport: SearchBackwardCompatibilitySupport,
    airbrake: AirbrakeNotifier,
    override val searchClient: DistributedSearchServiceClient,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait) extends SearchCommander with Sharding with Logging {

  def search(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUID: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false): DecoratedResult = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = SearchFilter(filter, LibraryContext.None, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    // build distribution plan
    val (localShards, dispatchPlan) = distributionPlan(userId, shards)

    val langsFuture = languageCommander.getLangs(localShards, dispatchPlan, userId, query, acceptLangs, LibraryContext.None)
    val (firstLang, secondLang) = monitoredAwait.result(langsFuture, 10 seconds, "slow getting lang profile")

    timing.presearch

    var resultFutures = new ListBuffer[Future[PartialSearchResult]]()

    if (dispatchPlan.nonEmpty) {
      // dispatch query
      searchClient.distSearch(dispatchPlan, userId, firstLang, secondLang, query, filter, maxHits, context, debug).foreach { f =>
        resultFutures += f.map(json => new PartialSearchResult(json))
      }
    }

    val (config, searchExperimentId) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val resultDecorator = {
      val showExperts = (filter.isEmpty && config.asBoolean("showExperts"))
      new ResultDecorator(userId, query, firstLang, showExperts, searchExperimentId, shoeboxClient, monitoredAwait)
    }

    // do the local part
    if (localShards.nonEmpty) {
      resultFutures += Promise[PartialSearchResult].complete(
        Try {
          distSearch(localShards, userId, firstLang, secondLang, experiments, query, filter, maxHits, context, predefinedConfig, debug)
        }
      ).future
    }

    val mergedResult = {

      val resultMerger = new ResultMerger(enableTailCutting, config, true)

      val results = monitoredAwait.result(Future.sequence(resultFutures), 10 seconds, "slow search")
      resultMerger.merge(results, maxHits)
    }
    timing.search

    val res = resultDecorator.decorate(mergedResult, searchFilter, withUriSummary)

    timing.postsearch
    timing.done

    SafeFuture {
      // stash timing information
      timing.send()

      val numPreviousHits = searchFilter.idFilter.size
      val lang = firstLang.lang + secondLang.map("," + _.lang).getOrElse("")
      val articleSearchResult = ResultUtil.toArticleSearchResult(
        res,
        lastUUID, // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
        mergedResult,
        timing.getTotalTime.toInt,
        numPreviousHits / maxHits,
        numPreviousHits,
        currentDateTime,
        lang
      )

      try {
        articleSearchResultStore += (res.uuid -> articleSearchResult)
      } catch {
        case e: Throwable => airbrake.notify(AirbrakeError(e, Some(s"Could not store article search result for user id $userId.")))
      }

      val timeLimit = 1000
      // search is a little slow after service restart. allow some grace period
      if (timing.getTotalTime > timeLimit && timing.timestamp - mainSearcherFactory.searchServiceStartedAt > 1000 * 60 * 8) {
        val link = "https://admin.kifi.com/admin/search/results/" + res.uuid.id
        val msg = s"search time exceeds limit! searchUUID = ${res.uuid.id}, Limit time = $timeLimit, ${timing.toString}. More details at: $link"
        airbrake.notify(msg)
      }
    }

    res
  }

  def distSearch(
    localShards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): PartialSearchResult = {

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)
    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    if (config.asBoolean("newEngine") == true) {
      val friendIdsFuture = searchFactory.getFriendIdsFuture(userId)
      val result = distSearch2(
        localShards,
        userId,
        firstLang,
        secondLang,
        experiments,
        query,
        filter,
        LibraryContext.None,
        maxHits,
        context,
        predefinedConfig,
        debug)

      val friendIds = monitoredAwait.result(friendIdsFuture, 3 seconds, "getting friend ids")
      return compatibilitySupport.toPartialSearchResult(localShards, userId, friendIds, result)
    }

    val searchFilter = SearchFilter(filter, LibraryContext.None, context)

    val searchers = mainSearcherFactory(localShards, userId, query, firstLang, secondLang, maxHits, searchFilter, config)

    val future = Future.traverse(searchers) { searcher =>
      if (debug.isDefined) searcher.debug(debug.get)

      SafeFuture { searcher.search() }
    }
    val results = monitoredAwait.result(future, 10 seconds, "slow search")

    val resultMerger = new ResultMerger(false, config, false)
    resultMerger.merge(results, maxHits)
  }

  def search2(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    libraryContextFuture: Future[LibraryContext],
    maxHits: Int,
    lastUUID: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String]): Future[KifiPlainResult] = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    if (debug.isDefined) log.info(s"DEBUG MODE: ${debug.get}")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    // build distribution plan
    val (localShards, dispatchPlan) = distributionPlan(userId, shards)

    val libraryContext = monitoredAwait.result(libraryContextFuture, 1 seconds, "getting library context")

    val langsFuture = languageCommander.getLangs(localShards, dispatchPlan, userId, query, acceptLangs, libraryContext)
    val (firstLang, secondLang) = monitoredAwait.result(langsFuture, 10 seconds, "slow getting lang profile")

    if (libraryContext == LibraryContext.Invalid) {
      // return an empty result for an invalid library public id
      return Future.successful(new KifiPlainResult(ExternalId[ArticleSearchResult](), query, KifiShardResult.empty, Set(), None))
    }

    val searchFilter = SearchFilter(filter, libraryContext, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    timing.presearch

    var resultFutures = new ListBuffer[Future[KifiShardResult]]()

    if (dispatchPlan.nonEmpty) {
      // dispatch query
      searchClient.distSearch2(dispatchPlan, userId, firstLang, secondLang, query, filter, libraryContext, maxHits, context, debug).foreach { f =>
        resultFutures += f.map(json => new KifiShardResult(json))
      }
    }

    // do the local part
    if (localShards.nonEmpty) {
      resultFutures += Promise[KifiShardResult].complete(
        Try {
          distSearch2(localShards, userId, firstLang, secondLang, experiments, query, filter, libraryContext, maxHits, context, predefinedConfig, debug)
        }
      ).future
    }

    Future.sequence(resultFutures).map { results =>
      val (config, searchExperimentId) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")
      val resultMerger = new KifiShardResultMerger(enableTailCutting, config)
      val mergedResult = resultMerger.merge(results, maxHits, withFinalScores = true)

      timing.search
      timing.postsearch
      timing.done

      val idFilter = searchFilter.idFilter ++ mergedResult.hits.map(_.id)
      val plainResult = KifiPlainResult(query, mergedResult, idFilter, searchExperimentId)

      SafeFuture {
        // stash timing information
        timing.send()

        val numPreviousHits = searchFilter.idFilter.size
        val lang = firstLang.lang + secondLang.map("," + _.lang).getOrElse("")
        val articleSearchResult = ResultUtil.toArticleSearchResult(
          plainResult,
          lastUUID, // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
          timing.getTotalTime.toInt,
          numPreviousHits / maxHits,
          numPreviousHits,
          currentDateTime,
          lang
        )

        try {
          articleSearchResultStore += (plainResult.uuid -> articleSearchResult)
        } catch {
          case e: Throwable => airbrake.notify(AirbrakeError(e, Some(s"Could not store article search result for user id $userId.")))
        }

        // search is a little slow after service restart. allow some grace period
        val timeLimit = 1000
        if (timing.getTotalTime > timeLimit && timing.timestamp - mainSearcherFactory.searchServiceStartedAt > 1000 * 60 * 8) {
          val link = "https://admin.kifi.com/admin/search/results/" + plainResult.uuid.id
          val msg = s"search time exceeds limit! searchUUID = ${plainResult.uuid.id}, Limit time = $timeLimit, ${timing.toString}. More details at: $link"
          airbrake.notify(msg)
        }
      }

      plainResult
    }
  }

  def distSearch2(
    localShards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    libraryContext: LibraryContext,
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): KifiShardResult = {

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val debugOption = new DebugOption with Logging
    if (debug.isDefined) debugOption.debug(debug.get)

    val searchFilter = SearchFilter(filter, libraryContext, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val searches = if (userId.id < 0 || (debugOption.flags & DebugOption.AsNonUser.flag) != 0) {
      try {
        searchFactory.getKifiNonUserSearch(localShards, query, firstLang, secondLang, maxHits, searchFilter, config)
      } catch {
        case e: Exception =>
          log.error("unable to create KifiNonUserSearch", e)
          Seq.empty[KifiSearch]
      }
    } else {
      // logged in user
      searchFactory.getKifiSearch(localShards, userId, query, firstLang, secondLang, maxHits, searchFilter, config)
    }

    val future = Future.traverse(searches) { search =>
      if (debug.isDefined) search.debug(debugOption)
      SafeFuture { search.execute() }
    }

    val results = monitoredAwait.result(future, 10 seconds, "slow search")

    val resultMerger = new KifiShardResultMerger(enableTailCutting, config)
    resultMerger.merge(results, maxHits)
  }

  //external (from the extension/website)
  def warmUp(userId: Id[User]) {
    SafeFuture {
      mainSearcherFactory.warmUp(userId)
    }
  }

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User]): Map[Lang, Int] = {
    monitoredAwait.result(mainSearcherFactory.distLangFreqsFuture(shards, userId), 10 seconds, "slow getting lang profile")
  }

  def explain(userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String], experiments: Set[ExperimentType], query: String): Option[(Query, Explanation)] = {
    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments)
    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val langs = lang match {
      case Some(str) => str.split(",").toSeq.map(Lang(_))
      case None => Seq(DefaultAnalyzer.defaultLang)
    }

    shards.find(uriId).flatMap { shard =>
      val searcher = mainSearcherFactory(shard, userId, query, langs(0), if (langs.size > 1) Some(langs(1)) else None, 0, SearchFilter.default(), config)
      searcher.explain(uriId)
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[SharingUserInfo] = {
    uriIds.map { uriId =>
      shards.find(uriId) match {
        case Some(shard) =>
          val searcher = mainSearcherFactory.getURIGraphSearcher(shard, userId)
          searcher.getSharingUserInfo(uriId)
        case None =>
          throw new Exception("shard not found")
      }
    }
  }

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

  private[this] def fetchUserDataInBackground(userId: Id[User]): Prefetcher = new Prefetcher(userId)

  private class Prefetcher(userId: Id[User]) {
    var futures: Seq[Future[Any]] = null // pin futures in a jvm heap
    SafeFuture {
      futures = mainSearcherFactory.warmUp(userId)
    }
  }

  class SearchTiming {
    val _startTime = System.currentTimeMillis()
    var _presearch = 0L
    var _search = 0L
    var _postsearch = 0L
    var _endTime = 0L

    def presearch: Unit = { _presearch = System.currentTimeMillis() }
    def search: Unit = { _search = System.currentTimeMillis() }
    def postsearch: Unit = { _postsearch = System.currentTimeMillis() }
    def done: Unit = { _endTime = System.currentTimeMillis() }

    def elapsed(time: Long = System.currentTimeMillis()): Long = (time - _startTime)

    def timestamp = _startTime + 1
    def getTotalTime: Long = (_endTime - _startTime)

    def send(): Unit = {
      send("extSearch.preSearchTime", _presearch, ALWAYS)
      send("extSearch.searching", _search, ALWAYS)
      send("extSearch.postSearchTime", _postsearch, ALWAYS)
      send("extSearch.total", _endTime, ALWAYS)
      statsd.incrementOne("extSearch.total", ONE_IN_TEN)
    }

    @inline
    private def send(name: String, time: Long, frequency: Double) = {
      if (time > 0L) statsd.timing(name, elapsed(time), frequency)
    }

    override def toString = {
      s"total time = ${elapsed(_endTime)}, pre-search time = ${elapsed(_presearch)}, search time = ${elapsed(_search)}, post-search time = ${elapsed(_postsearch)}"
    }
  }
}
