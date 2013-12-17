package com.keepit.controllers.ext

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsValue}
import play.modules.statsd.api.Statsd
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import com.google.inject.Inject
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.model.ExperimentType.NO_SEARCH_EXPERIMENTS
import com.keepit.search._
import com.keepit.search.LangDetector
import com.keepit.shoebox.ShoeboxServiceClient

class SearchCommander @Inject() (
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait,
  fortyTwoServices: FortyTwoServices
) extends Logging {

  def search(
    userId: Id[User],
    acceptLangs: Seq[String],
    noSearchExperiments: Boolean,
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None) : JsValue = {

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    log.info(s"""User ${userId} searched ${query.length} characters""")

    val searchFilter = getSearchFilter(userId, filter, context, start, end, tz, coll)
    val (config, searchExperimentId) = searchConfigManager.getConfigByUserSegment(userId, query, noSearchExperiments)

    timing.factory

    // TODO: use user profile info as a bias
    val lang = LangDetector.detectShortText(query, getLangsPriorProbabilities(acceptLangs))

    val searcher = mainSearcherFactory(userId, query, lang, maxHits, searchFilter, config)

    timing.search

    val mergedResult = {
      val shardRes = if (maxHits > 0) {
        searcher.search()
      } else {
        log.warn("maxHits is zero")
        ShardSearchResult.empty
      }

      ResultUtil.merge(Seq(shardRes), maxHits, config)
    }

    val experts = if (filter.isEmpty && config.asBoolean("showExperts")) {
      suggestExperts(mergedResult.hits)
    } else { Promise.successful(List.empty[Id[User]]).future }

    timing.decoration

    val searchUUID = ExternalId[ArticleSearchResult]()
    val newIdFilter = searchFilter.idFilter ++ mergedResult.hits.map(_.uriId.id)
    val numPreviousHits = searchFilter.idFilter.size
    val mayHaveMoreHits = if (numPreviousHits == 0) mergedResult.hits.nonEmpty else mergedResult.hits.size == maxHits
    val decorator = ResultDecorator(query, lang, mergedResult.friendStats, shoeboxClient, config, monitoredAwait)
    val res = toKifiSearchResult(
      searchUUID,
      query,
      decorator.decorate(mergedResult.hits),
      userId,
      newIdFilter,
      config,
      mayHaveMoreHits,
      (!searchFilter.isDefault || mergedResult.show),
      searchExperimentId,
      experts
    )

    timing.end

    SafeFuture {
      // stash timing information
      val timeLogs = searcher.timing()

      val lastUUID = for { str <- lastUUIDStr if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)
      val articleSearchResult = ResultUtil.toArticleSearchResult(
        searchUUID,
        lastUUID, // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
        query,
        mergedResult,
        mayHaveMoreHits,
        newIdFilter,
        timing.getTotalTime.toInt,
        numPreviousHits/maxHits,
        numPreviousHits,
        currentDateTime,
        lang
      )

      try {
        articleSearchResultStore += (searchUUID -> articleSearchResult)
      } catch {
        case e: Throwable => airbrake.notify(AirbrakeError(e, Some("Could not store article search result.")))
      }

      timing.send()

      log.info(timing.toString)

      val searchDetails =  "main-search details: " + timeLogs.toString
      log.info(searchDetails)

      val timeLimit = 1000
      // search is a little slow after service restart. allow some grace period
      if (timing.getTotalTime > timeLimit && timing.timestamp - fortyTwoServices.started.getMillis() > 1000*60*8) {
        val link = "https://admin.kifi.com/admin/search/results/" + searchUUID.id
        val msg = s"search time exceeds limit! searchUUID = ${searchUUID.id}, Limit time = $timeLimit, ${timing.toString}." +
            s" More details at: $link $searchDetails"
        airbrake.notify(msg)
      }
    }

    res
  }

  //external (from the extension/website)
  def warmUp(userId: Id[User]) {
    mainSearcherFactory.warmUp(userId)
  }

  private def getSearchFilter(
    userId: Id[User],
    filter: Option[String],
    context: Option[String],
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None
  ): SearchFilter = {
    filter match {
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

  private[ext] def toKifiSearchResult(
    uuid: ExternalId[ArticleSearchResult],
    query: String,
    decoratedResult: DecoratedResult,
    userId: Id[User],
    idFilter: Set[Long],
    config: SearchConfig,
    mayHaveMoreHits: Boolean,
    show: Boolean,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    expertsFuture: Future[Seq[Id[User]]]): JsValue = {

    val filter = IdFilterCompressor.fromSetToBase64(idFilter)
    val kifiHits = ResultUtil.toKifiSearchHits(decoratedResult.hits)
    val experts = monitoredAwait.result(expertsFuture, 100 milliseconds, s"suggesting experts", List.empty[Id[User]]).filter(_.id != userId.id).take(3)
    val expertNames = {
      if (experts.size == 0) List.empty[String]
      else {
        experts.flatMap{ expert => decoratedResult.users.get(expert).map{x => x.firstName + " " + x.lastName} }
      }
    }
    log.info("experts recommended: " + expertNames.mkString(" ; "))

    KifiSearchResult(
      uuid,
      query,
      kifiHits,
      mayHaveMoreHits,
      show,
      searchExperimentId,
      filter,
      Nil,
      expertNames).json
  }

  private def suggestExperts(hits: Seq[DetailedSearchHit]) = {
    val urisAndUsers = hits.map{ hit => (hit.uriId, hit.users) }
    if (urisAndUsers.map{_._2}.flatten.distinct.size < 2){
      Promise.successful(List.empty[Id[User]]).future
    } else{
      shoeboxClient.suggestExperts(urisAndUsers)
    }
  }

  private[this] def fetchUserDataInBackground(userId: Id[User]): Prefetcher = new Prefetcher(userId)

  private class Prefetcher(userId: Id[User]) {
    var futures: Seq[Future[Any]] = null // pin futures in a jvm heap
    SafeFuture{
      futures = mainSearcherFactory.warmUp(userId)
    }
  }

  class SearchTiming {
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

    def send(): Unit = {
      Statsd.timing("extSearch.factory", getFactoryTime)
      Statsd.timing("extSearch.searching", getSearchTime)
      Statsd.timing("extSearch.postSearchTime", getDecorationTime)
      Statsd.timing("extSearch.total", getTotalTime)
      Statsd.increment("extSearch.total")
    }

    override def toString = {
      s"total search time = $getTotalTime, pre-search time = $getPreSearchTime, search-factory time = $getFactoryTime, main-search time = $getSearchTime, post-search time = ${getDecorationTime}"
    }
  }
}
