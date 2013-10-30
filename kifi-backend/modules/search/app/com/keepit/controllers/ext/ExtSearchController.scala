package com.keepit.controllers.ext

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.future
import scala.concurrent.Future
import scala.util.Try
import com.google.inject.Inject
import com.keepit.common.controller.{AuthenticatedRequest, SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS
import com.keepit.search._
import com.keepit.serializer.PersonalSearchResultPacketSerializer.resSerializer
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext
import play.api.libs.json.Json
import com.keepit.common.db.{ExternalId, Id}
import com.newrelic.api.agent.Trace
import play.modules.statsd.api.Statsd
import scala.concurrent.Promise
import play.api.mvc.AnyContent
import com.keepit.heimdal.SearchAnalytics

class ExtSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient,
  searchAnalytics: SearchAnalytics,
  monitoredAwait: MonitoredAwait)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  @Trace
  def search(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None) = AuthenticatedJsonAction { request =>

    val timing = new SearchTiming

    val userId = request.userId
    val acceptLangs = request.request.acceptLanguages.map(_.code)
    val noSearchExperiments = request.experiments.contains(NO_SEARCH_EXPERIMENTS)

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(shoeboxClient, userId)

    log.info(s"""User ${userId} searched ${query.length} characters""")

    val searchFilter = filter match {
      case Some("m") =>
        val collExtIds = coll.map{ _.split('.').flatMap(id => Try(ExternalId[Collection](id)).toOption) }
        val collIdsFuture = collExtIds.map{ shoeboxClient.getCollectionIdsByExternalIds(_) }
        SearchFilter.mine(context, collIdsFuture, start, end, tz, monitoredAwait)
      case Some("f") =>
        SearchFilter.friends(context, start, end, tz)
      case Some("a") =>
        SearchFilter.all(context, start, end, tz)
      case Some(ids) =>
        val userExtIds = ids.split('.').flatMap(id => Try(ExternalId[User](id)).toOption)
        val userIdsFuture = shoeboxClient.getUserIdsByExternalIds(userExtIds)
        SearchFilter.custom(context, userIdsFuture, start, end, tz, monitoredAwait)
      case None =>
        if (start.isDefined || end.isDefined) SearchFilter.all(context, start, end, tz)
        else SearchFilter.default(context)
    }

    val (config, searchExperimentId) = searchConfigManager.getConfig(userId, query, noSearchExperiments)

    val lastUUID = for { str <- lastUUIDStr if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)

    timing.factory

    val probabilities = getLangsPriorProbabilities(acceptLangs)
    val searcher = mainSearcherFactory(userId, query, probabilities, maxHits, searchFilter, config, lastUUID)

    timing.search

    val searchRes = if (maxHits > 0) {
      searcher.search()
    } else {
      log.warn("maxHits is zero")
      val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
      ArticleSearchResult(lastUUID, query, Seq.empty[ArticleHit], 0, 0, true, Seq.empty[Scoring], idFilter, 0, Int.MaxValue)
    }

    val experts = if (filter.isEmpty && config.asBoolean("showExperts")) {
      suggestExperts(searchRes)
    } else { Promise.successful(List.empty[Id[User]]).future }

    timing.decoration

    val decorator = ResultDecorator(searcher, shoeboxClient, config, monitoredAwait)
    val res = toPersonalSearchResultPacket(decorator, userId, searchRes, config, searchFilter.isDefault, searchExperimentId, experts)

    timing.end

    SafeFuture {
      // stash timing information
      searcher.timing()

      try {
        reportSearch(request,kifiVersion, maxHits, searchFilter, searchExperimentId, searchRes)
      } catch {
        case e: Throwable => log.error("Could not report search %s".format(res), e)
      }

      Statsd.timing("extSearch.factory", timing.getFactoryTime)
      Statsd.timing("extSearch.searching", timing.getSearchTime)
      Statsd.timing("extSearch.postSearchTime", timing.getDecorationTime)
      Statsd.timing("extSearch.total", timing.getTotalTime)
      Statsd.increment("extSearch.total")

      log.info(timing.toString)

      val searchDetails = searchRes.timeLogs match {
        case Some(timelog) => "main-search detail: " + timelog.toString
        case None => "main-search detail: N/A"
      }
      log.info(searchDetails)

      val timeLimit = 1000
      // search is a little slow after service restart. allow some grace period
      if (timing.getTotalTime > timeLimit && timing.timestamp - fortyTwoServices.started.getMillis() > 1000*60*8) {
        val link = "https://admin.kifi.com/admin/search/results/" + searchRes.uuid.id
        val msg = s"search time exceeds limit! searchUUID = ${searchRes.uuid.id}, Limit time = $timeLimit, ${timing.toString}." +
            s" More details at: $link $searchDetails"
        airbrake.notify(msg)
      }
    }

    Ok(Json.toJson(res)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  private def getLangsPriorProbabilities(acceptLangs: Seq[String]): Map[Lang, Double] = {
    val majorLangs = acceptLangs.toSet.flatMap{ code: String =>
      val lang = code.substring(0,2)
      if (lang == "zh") Set("zh-cn", "zh-tw") else Set(lang)
    } + "en" // always include English

    val majorLangProb = 0.99999d
    val numberOfLangs = majorLangs.size
    val eachLangProb = (majorLangProb / numberOfLangs)
    majorLangs.map{ (Lang(_) -> eachLangProb) }.toMap
  }

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

  private[ext] def toPersonalSearchResultPacket(
    decorator: ResultDecorator,
    userId: Id[User],
    res: ArticleSearchResult,
    config: SearchConfig,
    isDefaultFilter: Boolean,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    expertsFuture: Future[Seq[Id[User]]]): PersonalSearchResultPacket = {

    val decoratedResult = decorator.decorate(res)
    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    val experts = monitoredAwait.result(expertsFuture, 100 milliseconds, s"suggesting experts", List.empty[Id[User]]).filter(_.id != userId.id).take(3)
    val expertNames = {
      if (experts.size == 0) List.empty[String]
      else {
        val idMap = decoratedResult.users
        experts.flatMap{ expert => idMap.get(expert).map{x => x.firstName + " " + x.lastName} }
      }
    }
    log.info("experts recommended: " + expertNames.mkString(" ; "))

    PersonalSearchResultPacket(res.uuid, res.query, decoratedResult.hits,
      res.mayHaveMoreHits, (!isDefaultFilter || res.toShow), searchExperimentId, filter, expertNames)
  }

  private[ext] def suggestExperts(searchRes: ArticleSearchResult) = {
    val urisAndUsers = searchRes.hits.map{ hit =>
      (hit.uriId, hit.users)
    }
    if (urisAndUsers.map{_._2}.flatten.distinct.size < 2){
      Promise.successful(List.empty[Id[User]]).future
    } else{
      shoeboxClient.suggestExperts(urisAndUsers)
    }
  }

  private[this] def fetchUserDataInBackground(shoeboxClient: ShoeboxServiceClient, userId: Id[User]): Prefetcher = new Prefetcher(shoeboxClient, userId)

  private class Prefetcher(shoeboxClient: ShoeboxServiceClient, userId: Id[User]) {
    var futures: Seq[Future[Any]] = null // pin futures in a jvm heap
    future {
      // following request must have request consolidation enabled, otherwise no use.
      // have a head start on every other requests that search will make in order, then submit skipped requests backwards
      futures = Seq(
        // skip every other
        shoeboxClient.getSearchFriends(userId),
        // then, backwards
        shoeboxClient.getFriends(userId)
      )
    }
  }

  private def reportSearch(
    request: AuthenticatedRequest[AnyContent],
    kifiVersion: Option[KifiVersion],
    maxHits: Int,
    searchFilter: SearchFilter,
    searchExperiment: Option[Id[SearchConfigExperiment]],
    res: ArticleSearchResult) {

    articleSearchResultStore += (res.uuid -> res)
    searchAnalytics.searchPerformed(request, kifiVersion, maxHits, searchFilter, searchExperiment, res)
  }

  class SearchTiming{
    val t1 = currentDateTime.getMillis()
    var t2 = t1
    var t3 = t1
    var t4 = t1
    var t5 = t1

    def factory: Unit = { t2 = currentDateTime.getMillis }
    def search: Unit = { t3 = currentDateTime.getMillis }
    def decoration: Unit = { t4 = currentDateTime.getMillis }
    def end: Unit = { t5 = currentDateTime.getMillis() }

    def timestamp = t1

    def getPreSearchTime = (t2 - t1)
    def getFactoryTime = (t3 - t2)
    def getSearchTime = (t4 - t3)
    def getDecorationTime = (t5 - t4)
    def getTotalTime: Long = (t5 - t1)

    override def toString = {
      s"total search time = $getTotalTime, pre-search time = $getPreSearchTime, search-factory time = $getFactoryTime, main-search time = $getSearchTime, post-search time = ${getDecorationTime}"
    }
  }
}
