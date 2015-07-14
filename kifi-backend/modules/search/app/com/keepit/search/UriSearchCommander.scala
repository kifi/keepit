package com.keepit.search

import com.keepit.common.store.S3ImageConfig
import com.keepit.rover.RoverServiceClient
import com.keepit.search.engine.uri.{ UriSearch, UriShardResultMerger, UriShardResult, UriSearchResult, UriSearchExplanation }
import com.keepit.search.engine.{ DebugOption, SearchFactory }
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.concurrent.{ Future }
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.sharding.{ Sharding, Shard, ActiveShards }
import com.keepit.search.result._
import com.keepit.search.index.DefaultAnalyzer

case class UriSearchRequest(
  userId: Id[User],
  experiments: Set[ExperimentType],
  query: String,
  filter: SearchFilter,
  orderBy: SearchRanking,
  firstLang: Lang,
  secondLang: Option[Lang],
  maxHits: Int,
  predefinedConfig: Option[SearchConfig],
  debug: Option[String])

object UriSearchRequest {
  implicit val format = Json.format[UriSearchRequest]
}

@ImplementedBy(classOf[UriSearchCommanderImpl])
trait UriSearchCommander {

  def searchUris(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filterFuture: Future[SearchFilter],
    orderBy: SearchRanking,
    maxHits: Int,
    lastUUIDStr: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): Future[UriSearchResult]

  def distSearchUris(shards: Set[Shard[NormalizedURI]], request: UriSearchRequest): Future[UriShardResult]

  def explain(
    userId: Id[User],
    uriId: Id[NormalizedURI],
    libraryId: Option[Id[Library]],
    lang: Option[String],
    experiments: Set[ExperimentType],
    query: String,
    debug: Option[String]): Future[Option[UriSearchExplanation]]

  def warmUp(userId: Id[User]): Unit

  def findShard(uriId: Id[NormalizedURI]): Option[Shard[NormalizedURI]]
}

@Singleton
class UriSearchCommanderImpl @Inject() (
    shards: ActiveShards,
    searchFactory: SearchFactory,
    languageCommander: LanguageCommander,
    articleSearchResultStore: ArticleSearchResultStore,
    airbrake: AirbrakeNotifier,
    override val searchClient: DistributedSearchServiceClient,
    shoeboxClient: ShoeboxServiceClient,
    rover: RoverServiceClient,
    monitoredAwait: MonitoredAwait,
    imageConfig: S3ImageConfig) extends UriSearchCommander with Sharding with Logging {

  implicit private[this] val defaultExecutionContext = fj

  def searchUris(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filterFuture: Future[SearchFilter],
    orderBy: SearchRanking,
    maxHits: Int,
    lastUUID: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String]): Future[UriSearchResult] = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    if (debug.isDefined) log.info(s"DEBUG MODE: ${debug.get}")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = searchFactory.getConfigFuture(userId, experiments, predefinedConfig)

    // build distribution plan
    val (localShards, dispatchPlan) = distributionPlan(userId, shards)

    val filter = monitoredAwait.result(filterFuture, 2 second, "getting user filter")

    val langsFuture = languageCommander.getLangs(localShards, dispatchPlan, userId, query, acceptLangs, filter.library)

    val (firstLang, secondLang) = monitoredAwait.result(langsFuture, 10 seconds, "slow getting lang profile")

    timing.presearch

    val request = UriSearchRequest(
      userId,
      experiments,
      query,
      filter,
      orderBy,
      firstLang,
      secondLang,
      maxHits,
      predefinedConfig,
      debug
    )

    val futureRemoteLibraryShardResults = searchClient.distSearchUris(dispatchPlan, request)
    val futureLocalLibraryShardResult = distSearchUris(localShards, request)
    val futureResults = Future.sequence(futureRemoteLibraryShardResults :+ futureLocalLibraryShardResult)

    futureResults.map { results =>
      val enableTailCutting = (filter.isDefault && filter.idFilter.isEmpty)
      val (config, searchExperimentId) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")
      val resultMerger = new UriShardResultMerger(enableTailCutting, config)
      val mergedResult = resultMerger.merge(results, maxHits, withFinalScores = true)

      timing.search
      timing.postsearch
      timing.done

      val idFilter = filter.idFilter ++ mergedResult.hits.map(_.id)
      val plainResult = UriSearchResult(query, filter, firstLang, mergedResult, idFilter, searchExperimentId)

      SafeFuture {
        // stash timing information
        timing.send()

        val numPreviousHits = filter.idFilter.size
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
        val timeLimit = 1500
        if (timing.getTotalTime > timeLimit && timing.timestamp - searchFactory.searchServiceStartedAt > 1000 * 60 * 8) {
          val link = "https://admin.kifi.com/admin/search/results/" + plainResult.uuid.id
          val msg = s"search time exceeds limit! searchUUID = ${plainResult.uuid.id}, Limit time = $timeLimit, ${timing.toString}. More details at: $link"
          airbrake.notify(msg)
        }
      }(singleThread)

      plainResult
    }
  }

  def distSearchUris(shards: Set[Shard[NormalizedURI]], request: UriSearchRequest): Future[UriShardResult] = {

    val configFuture = searchFactory.getConfigFuture(request.userId, request.experiments, request.predefinedConfig)

    val debugOption = new DebugOption with Logging
    if (request.debug.isDefined) debugOption.debug(request.debug.get)

    val searchFilter = request.filter
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val searches = if (request.userId.id < 0 || (debugOption.debugFlags & DebugOption.AsNonUser.flag) != 0) {
      try {
        searchFactory.getNonUserUriSearches(shards, request.query, request.firstLang, request.secondLang, request.maxHits, searchFilter, request.orderBy, config)
      } catch {
        case e: Exception =>
          log.error("unable to create KifiNonUserSearch", e)
          Seq.empty[UriSearch]
      }
    } else {
      // logged in user
      searchFactory.getUriSearches(shards, request.userId, request.query, request.firstLang, request.secondLang, request.maxHits, searchFilter, request.orderBy, config, request.experiments)
    }

    Future.traverse(searches) { search =>
      if (request.debug.isDefined) search.debug(debugOption)
      SafeFuture { search.execute() }
    }.map { results =>
      val resultMerger = new UriShardResultMerger(enableTailCutting, config)
      resultMerger.merge(results, request.maxHits)
    }
  }

  //external (from the extension/website)
  def warmUp(userId: Id[User]): Unit = searchFactory.warmUp(userId)

  def findShard(uriId: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = shards.find(uriId)

  def explain(userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Id[Library]], lang: Option[String], experiments: Set[ExperimentType], query: String, debug: Option[String]): Future[Option[UriSearchExplanation]] = {
    val langs = lang match {
      case Some(str) => str.split(",").toSeq.map(Lang(_))
      case None => Seq(DefaultAnalyzer.defaultLang)
    }
    val firstLang = langs(0)
    val secondLang = langs.lift(1)

    val searchFilter = SearchFilter(proximity = None, user = None, library = libraryId.map(LibraryScope(_, authorized = true)), organization = None, context = None)

    searchFactory.getConfigFuture(userId, experiments).map {
      case (config, _) =>
        findShard(uriId).flatMap { shard =>
          val searchOpt = if (userId.id < 0) {
            try {
              searchFactory.getNonUserUriSearches(Set(shard), query, firstLang, secondLang, 0, searchFilter, SearchRanking.default, config).headOption
            } catch {
              case e: Exception =>
                log.error("unable to create KifiNonUserSearch", e)
                None
            }
          } else {
            searchFactory.getUriSearches(Set(shard), userId, query, firstLang, secondLang, 0, searchFilter, SearchRanking.default, config, experiments).headOption
          }
          searchOpt.map { search =>
            debug.map(search.debug(_))
            search.explain(uriId)
          }
        }
    }
  }

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

  private[this] def fetchUserDataInBackground(userId: Id[User]): Prefetcher = new Prefetcher(userId)

  private class Prefetcher(userId: Id[User]) {
    // pin futures in a jvm heap
    val futures: Seq[Future[Any]] = searchFactory.warmUp(userId)
  }

  class SearchTiming {
    val _startTime = System.currentTimeMillis()
    var _presearch = 0L
    var _search = 0L
    var _postsearch = 0L
    var _endTime = 0L

    def presearch(): Unit = { _presearch = System.currentTimeMillis() }
    def search(): Unit = { _search = System.currentTimeMillis() }
    def postsearch(): Unit = { _postsearch = System.currentTimeMillis() }
    def done(): Unit = { _endTime = System.currentTimeMillis() }

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
