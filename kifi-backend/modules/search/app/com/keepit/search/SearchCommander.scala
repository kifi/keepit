package com.keepit.search

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
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.sharding.{ Sharding, DispatchFailedException, Shard, ActiveShards }
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
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
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
    start: Option[String],
    end: Option[String],
    tz: Option[String],
    coll: Option[String],
    debug: Option[String]): PartialSearchResult

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
    mainSearcherFactory: MainSearcherFactory,
    articleSearchResultStore: ArticleSearchResultStore,
    airbrake: AirbrakeNotifier,
    override val searchClient: SearchServiceClient,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait) extends SearchCommander with Sharding with Logging {

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
    debug: Option[String] = None,
    withUriSummary: Boolean = false): DecoratedResult = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(userId, filter, context, start, end, tz, coll)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    // build distribution plan
    val (localShards, dispatchPlan) = {
      // do distributed search using a remote-only plan when the debug flag has "dist" for now. still experimental
      if (debug.isDefined && debug.get.indexOf("dist") >= 0) {
        distributionPlanRemoteOnly(userId, maxShardsPerInstance = 2)
      } else {
        distributionPlan(userId, shards)
      }
    }

    // TODO: use user profile info as a bias
    val (firstLang, secondLang) = getLangs(localShards, dispatchPlan, userId, query, acceptLangs) // TODO: distributed version of getLang

    var resultFutures = new ListBuffer[Future[PartialSearchResult]]()

    if (dispatchPlan.nonEmpty) {
      // dispatch query
      searchClient.distSearch(dispatchPlan, userId, firstLang, secondLang, query, filter, maxHits, context, start, end, tz, coll, debug).foreach { f =>
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
          distSearch(localShards, userId, firstLang, secondLang, experiments, query, filter, maxHits, context, predefinedConfig, start, end, tz, coll, debug)
        }
      ).future
    }

    val mergedResult = {

      val resultMerger = new ResultMerger(enableTailCutting, config)

      timing.search
      val results = monitoredAwait.result(Future.sequence(resultFutures), 10 seconds, "slow search")
      resultMerger.merge(results, maxHits)
    }

    timing.decoration

    val res = resultDecorator.decorate(mergedResult, searchFilter, withUriSummary)

    timing.end

    SafeFuture {
      // stash timing information
      timing.sendTotal()

      val lastUUID = for { str <- lastUUIDStr if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)
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
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None): PartialSearchResult = {

    val timing = new SearchTiming

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(userId, filter, context, start, end, tz, coll)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val mergedResult = {
      val resultMerger = new ResultMerger(enableTailCutting, config)

      timing.factory

      val searchers = mainSearcherFactory(shards, userId, query, firstLang, secondLang, maxHits, searchFilter, config)
      val future = Future.traverse(searchers) { searcher =>
        if (debug.isDefined) searcher.debug(debug.get)

        SafeFuture { searcher.search() }
      }

      timing.search
      val results = monitoredAwait.result(future, 10 seconds, "slow search")
      resultMerger.merge(results, maxHits)
    }

    timing.decoration // search end
    timing.end

    SafeFuture {
      // stash timing information
      timing.send()
    }

    mergedResult
  }

  //external (from the extension/website)
  def warmUp(userId: Id[User]) {
    SafeFuture {
      mainSearcherFactory.warmUp(userId)
    }
  }

  private def getLangs(
    localShards: Set[Shard[NormalizedURI]],
    dispatchPlan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    query: String,
    acceptLangCodes: Seq[String]): (Lang, Option[Lang]) = {
    def getLangsPriorProbabilities(majorLangs: Set[Lang], majorLangProb: Double): Map[Lang, Double] = {
      val numberOfLangs = majorLangs.size
      val eachLangProb = (majorLangProb / numberOfLangs)
      majorLangs.map(_ -> eachLangProb).toMap
    }

    // TODO: use user profile info as a bias

    val resultFutures = new ListBuffer[Future[Map[Lang, Int]]]()

    if (dispatchPlan.nonEmpty) {
      resultFutures ++= searchClient.distLangFreqs(dispatchPlan, userId)
    }
    if (localShards.nonEmpty) {
      resultFutures += mainSearcherFactory.distLangFreqsFuture(localShards, userId)
    }

    val acceptLangs = {
      val langs = acceptLangCodes.toSet.flatMap { code: String =>
        val langCode = code.substring(0, 2)
        if (langCode == "zh") Set(Lang("zh-cn"), Lang("zh-tw"))
        else {
          val lang = Lang(langCode)
          if (LangDetector.languages.contains(lang)) Set(lang) else Set.empty[Lang]
        }
      }
      if (langs.isEmpty) {
        log.warn(s"defaulting to English for acceptLang=$acceptLangCodes")
        Set(DefaultAnalyzer.defaultLang)
      } else {
        langs
      }
    }

    val langProf = {
      val freqs = monitoredAwait.result(Future.sequence(resultFutures), 10 seconds, "slow getting lang profile")
      val total = freqs.map(_.values.sum).sum.toFloat
      freqs.map(_.iterator).flatten.foldLeft(Map[Lang, Float]()) {
        case (m, (lang, count)) =>
          m + (lang -> (count.toFloat / total + m.getOrElse(lang, 0.0f)))
      }.filter { case (_, prob) => prob > 0.05f }.toSeq.sortBy(p => -p._2).take(3).toMap // top N with prob > 0.05
    }

    val profLangs = langProf.keySet

    var strongCandidates = acceptLangs ++ profLangs

    val firstLang = LangDetector.detectShortText(query, getLangsPriorProbabilities(strongCandidates, 0.6d))
    strongCandidates -= firstLang
    val secondLang = if (strongCandidates.nonEmpty) {
      Some(LangDetector.detectShortText(query, getLangsPriorProbabilities(strongCandidates, 1.0d)))
    } else {
      None
    }

    // we may switch first/second langs
    if (acceptLangs.contains(firstLang)) {
      (firstLang, secondLang)
    } else if (acceptLangs.contains(secondLang.get)) {
      (secondLang.get, Some(firstLang))
    } else if (profLangs.contains(firstLang)) {
      (firstLang, secondLang)
    } else {
      (secondLang.get, Some(firstLang))
    }
  }

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User]): Map[Lang, Int] = {
    monitoredAwait.result(mainSearcherFactory.distLangFreqsFuture(shards, userId), 10 seconds, "slow getting lang profile")
  }

  private def getSearchFilter(
    userId: Id[User],
    filter: Option[String],
    context: Option[String],
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None): SearchFilter = {
    filter match {
      case Some("m") =>
        val collExtIds = coll.map { _.split('.').flatMap(id => Try(ExternalId[Collection](id)).toOption) }
        val collIdsFuture = collExtIds.map { shoeboxClient.getCollectionIdsByExternalIds(_) }
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
    val t1 = currentDateTime.getMillis()
    var t2 = t1
    var t3 = t1
    var t4 = t1
    var t5 = t1

    def factory: Unit = { t2 = currentDateTime.getMillis }
    def search: Unit = { t3 = currentDateTime.getMillis }
    def decoration: Unit = { t4 = currentDateTime.getMillis }
    def end: Unit = { t5 = currentDateTime.getMillis() }

    def timestamp = t1 + 1

    def getPreSearchTime = (t2 - t1)
    def getFactoryTime = (t3 - t2)
    def getSearchTime = (t4 - t3)
    def getDecorationTime = (t5 - t4)
    def getTotalTime: Long = (t5 - t1)

    def send(): Unit = {
      statsd.timing("extSearch.factory", getFactoryTime, ALWAYS)
      statsd.timing("extSearch.searching", getSearchTime, ALWAYS)
    }

    def sendTotal(): Unit = {
      statsd.timing("extSearch.postSearchTime", getDecorationTime, ALWAYS)
      statsd.timing("extSearch.total", getTotalTime, ALWAYS)
      statsd.incrementOne("extSearch.total", ONE_IN_TEN)
    }

    override def toString = {
      s"total search time = $getTotalTime, pre-search time = $getPreSearchTime, search-factory time = $getFactoryTime, main-search time = $getSearchTime, post-search time = ${getDecorationTime}"
    }
  }
}
