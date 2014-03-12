package com.keepit.search

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsValue}
import play.modules.statsd.api.Statsd
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import com.google.inject.{ImplementedBy, Inject}
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
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.result.ShardSearchResult
import com.keepit.search.result.ResultDecorator
import com.keepit.search.result.DecoratedResult
import com.keepit.search.result.ResultMerger
import com.keepit.search.result.ResultUtil

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
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None) : DecoratedResult

  def warmUp(userId: Id[User]): Unit
}

class SearchCommanderImpl @Inject() (
  shards: ActiveShards,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait,
  fortyTwoServices: FortyTwoServices
) extends SearchCommander with Logging {

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
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None) : DecoratedResult = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val searchFilter = getSearchFilter(userId, filter, context, start, end, tz, coll)
    val (config, searchExperimentId) = predefinedConfig match {
      case None => searchConfigManager.getConfig(userId, experiments)
      case Some(conf) =>
        val default = searchConfigManager.defaultConfig
        (new SearchConfig(default.params ++ conf.params), None)      // almost complete overwrite. But when search config parameter list changes, this prevents exception
    }

    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    // TODO: use user profile info as a bias
    val (firstLang, secondLang) = getLangs(userId, query, acceptLangs)
    val resultDecorator = {
      val showExperts = (filter.isEmpty && config.asBoolean("showExperts"))
      new ResultDecorator(userId, query, firstLang, showExperts, shoeboxClient, monitoredAwait)
    }

    val mergedResult = {
      timing.factory
      val future = Future.traverse(shards.shards){ shard =>
        SafeFuture{
          val searcher = mainSearcherFactory(shard, userId, query, firstLang, secondLang, maxHits, searchFilter, config)
          debug.foreach{ searcher.debug(_) }
          searcher.search()
        }
      }

      timing.search
      val results = monitoredAwait.result(future, 10 seconds, "slow search")
      ResultMerger.merge(results, maxHits, enableTailCutting, config)
    }

    timing.decoration

    val newIdFilter = searchFilter.idFilter ++ mergedResult.hits.map(_.uriId.id)
    val mayHaveMoreHits = (mergedResult.hits.size < (mergedResult.myTotal + mergedResult.friendsTotal + mergedResult.othersTotal))
    val res = resultDecorator.decorate(mergedResult, mayHaveMoreHits, searchExperimentId, newIdFilter)

    timing.end

    SafeFuture {
      // stash timing information
      timing.send()

      val lastUUID = for { str <- lastUUIDStr if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)
      val numPreviousHits = searchFilter.idFilter.size
      val lang = firstLang.lang + secondLang.map("," + _.lang).getOrElse("")
      val articleSearchResult = ResultUtil.toArticleSearchResult(
        res,
        lastUUID, // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
        mergedResult,
        timing.getTotalTime.toInt,
        numPreviousHits/maxHits,
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
      if (timing.getTotalTime > timeLimit && timing.timestamp - fortyTwoServices.started.getMillis() > 1000*60*8) {
        val link = "https://admin.kifi.com/admin/search/results/" + res.uuid.id
        val msg = s"search time exceeds limit! searchUUID = ${res.uuid.id}, Limit time = $timeLimit, ${timing.toString}. More details at: $link"
        airbrake.notify(msg)
      }
    }

    res
  }

  //external (from the extension/website)
  def warmUp(userId: Id[User]) {
    SafeFuture {
      mainSearcherFactory.warmUp(userId)
    }
  }

  private def getLangs(userId: Id[User], query: String, acceptLangCodes: Seq[String]): (Lang, Option[Lang]) = {
    def getLangsPriorProbabilities(majorLangs: Set[Lang], majorLangProb: Double): Map[Lang, Double] = {
      val numberOfLangs = majorLangs.size
      val eachLangProb = (majorLangProb / numberOfLangs)
      majorLangs.map(_ -> eachLangProb).toMap
    }

    // TODO: use user profile info as a bias
    var acceptLangs = acceptLangCodes.toSet.flatMap{ code: String =>
      val langCode = code.substring(0,2)
      if (langCode == "zh") Set(Lang("zh-cn"), Lang("zh-tw"))
      else {
        val lang = Lang(langCode)
        if (LangDetector.languages.contains(lang)) Set(lang) else Set.empty[Lang]
      }
    }

    if (acceptLangs.isEmpty) {
      log.warn(s"defaulting to English for acceptLang=$acceptLangCodes")
      acceptLangs = Set(Lang("en"))
    }

    val langProf = getLangProfile(userId, 3)
    val firstLangSet = acceptLangs ++ langProf.keySet

    val firstLang = LangDetector.detectShortText(query, getLangsPriorProbabilities(firstLangSet, 0.9d))
    val secondLangSet = (firstLangSet - firstLang)
    val secondLang = if (secondLangSet.nonEmpty) {
      Some(LangDetector.detectShortText(query, getLangsPriorProbabilities(secondLangSet, 1.0d)))
    } else {
      None
    }
    (firstLang, secondLang)
  }

  private def getLangProfile(userId: Id[User], limit: Int): Map[Lang, Float] = { // todo: cache
    val future = Future.traverse(shards.shards){ shard =>
      SafeFuture{
        val searcher = mainSearcherFactory.getURIGraphSearcher(shard, userId)
        searcher.getLangProfile()
      }
    }
    val results = monitoredAwait.result(future, 10 seconds, "slow getting lang profile")
    val total = results.map(_.values.sum).sum.toFloat
    if (total > 0) {
      val newProf = results.map(_.iterator).flatten.foldLeft(Map[Lang, Float]()){ case (m, (lang, count)) =>
        m + (lang -> (count.toFloat/total + m.getOrElse(lang, 0.0f).toFloat))
      }
      newProf.filter{ case (_, prob) => prob > 0.05f }.toSeq.sortBy(p => - p._2).take(limit).toMap // top N with prob > 0.05
    } else {
      Map()
    }
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

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

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
