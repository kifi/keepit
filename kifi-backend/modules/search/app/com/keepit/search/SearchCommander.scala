package com.keepit.search

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.search.engine.{ Visibility, SearchFactory }
import com.keepit.search.engine.result.{ KifiPlainResult, KifiShardHit, KifiShardResultMerger, KifiShardResult }
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
import com.keepit.search.sharding.{ Sharding, Shard, ActiveShards }
import com.keepit.search.result._
import org.apache.lucene.search.{ Explanation, Query }
import com.keepit.search.index.DefaultAnalyzer
import scala.collection.mutable.ListBuffer
import scala.math

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
    library: Option[String],
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
    library: Option[String],
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
    mainSearcherFactory: MainSearcherFactory,
    articleSearchResultStore: ArticleSearchResultStore,
    airbrake: AirbrakeNotifier,
    override val searchClient: SearchServiceClient,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait,
    implicit val publicIdConfig: PublicIdConfiguration) extends SearchCommander with Sharding with Logging {

  private[this] lazy val compatibilitySupport = new SearchCommanderBackwardCompatibilitySupport(shards, searchFactory, mainSearcherFactory)

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
    withUriSummary: Boolean = false): DecoratedResult = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(filter, None, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    // build distribution plan
    val (localShards, dispatchPlan) = distributionPlan(userId, shards)

    // TODO: use user profile info as a bias
    val (firstLang, secondLang) = getLangs(localShards, dispatchPlan, userId, query, acceptLangs)

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

    if (debug.isDefined) {
      val debugFlags = debug.get.split(",").map(_.toLowerCase).toSet
      if (debugFlags.contains("newengine")) {
        val friendIdsFuture = searchFactory.getFriendIdsFuture(userId)
        val result = distSearch2(
          localShards,
          userId,
          firstLang,
          secondLang,
          experiments,
          query,
          filter,
          None,
          maxHits,
          context,
          predefinedConfig,
          debug)

        val friendIds = monitoredAwait.result(friendIdsFuture, 3 seconds, "getting friend ids")
        return compatibilitySupport.toPartialSearchResult(userId, friendIds, result)
      }
    }

    val timing = new SearchTiming

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(filter, None, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val mergedResult = {
      val resultMerger = new ResultMerger(enableTailCutting, config)

      timing.factory

      val searchers = mainSearcherFactory(localShards, userId, query, firstLang, secondLang, maxHits, searchFilter, config)
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

  def search2(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    library: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String]): Future[KifiPlainResult] = {

    if (maxHits <= 0) throw new IllegalArgumentException("maxHits is zero")

    val timing = new SearchTiming

    // fetch user data in background
    val prefetcher = fetchUserDataInBackground(userId)

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(filter, library, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    // build distribution plan
    val (localShards, dispatchPlan) = distributionPlan(userId, shards)

    // TODO: use user profile info as a bias
    val (firstLang, secondLang) = getLangs(localShards, dispatchPlan, userId, query, acceptLangs)

    var resultFutures = new ListBuffer[Future[KifiShardResult]]()

    if (dispatchPlan.nonEmpty) {
      // dispatch query
      searchClient.distSearch2(dispatchPlan, userId, firstLang, secondLang, query, filter, library, maxHits, context, debug).foreach { f =>
        resultFutures += f.map(json => new KifiShardResult(json))
      }
    }

    // do the local part
    if (localShards.nonEmpty) {
      resultFutures += Promise[KifiShardResult].complete(
        Try {
          distSearch2(localShards, userId, firstLang, secondLang, experiments, query, filter, library, maxHits, context, predefinedConfig, debug)
        }
      ).future
    }

    timing.search

    Future.sequence(resultFutures).map { results =>
      log.info("NE: merging result")

      val (config, searchExperimentId) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")
      val resultMerger = new KifiShardResultMerger(enableTailCutting, config)
      val mergedResult = resultMerger.merge(results, maxHits)

      timing.decoration
      timing.end

      val idFilter = searchFilter.idFilter ++ mergedResult.hits.map(_.id)
      val plainResult = KifiPlainResult(query, mergedResult, idFilter, searchExperimentId)

      log.info("NE: plain result created")

      SafeFuture {
        // stash timing information
        timing.sendTotal()

        // TODO: save ArticleSearchResult

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
    library: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    debug: Option[String] = None): KifiShardResult = {

    val timing = new SearchTiming

    val configFuture = mainSearcherFactory.getConfigFuture(userId, experiments, predefinedConfig)

    val searchFilter = getSearchFilter(filter, library, context)
    val enableTailCutting = (searchFilter.isDefault && searchFilter.idFilter.isEmpty)

    val (config, _) = monitoredAwait.result(configFuture, 1 seconds, "getting search config")

    val resultMerger = new KifiShardResultMerger(enableTailCutting, config)

    timing.factory

    val searches = if (userId.id >= 0) {
      // logged in user
      searchFactory.getKifiSearch(localShards, userId, query, firstLang, secondLang, maxHits, searchFilter, config)
    } else {
      searchFactory.getKifiNonUserSearch(localShards, searchFilter.libraryId.get, query, firstLang, secondLang, maxHits, searchFilter, config)
    }

    val future = Future.traverse(searches) { search =>
      SafeFuture { search.execute() }
    }

    timing.search // search start

    val results = monitoredAwait.result(future, 10 seconds, "slow search")
    val mergedResult = resultMerger.merge(results, maxHits)

    timing.decoration // search end, no decoration
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

    val acceptLangs = parseAcceptLangs(acceptLangCodes)

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

  private def parseAcceptLangs(acceptLangCodes: Seq[String]): Set[Lang] = {
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

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User]): Map[Lang, Int] = {
    monitoredAwait.result(mainSearcherFactory.distLangFreqsFuture(shards, userId), 10 seconds, "slow getting lang profile")
  }

  private def getSearchFilter(
    filter: Option[String],
    library: Option[String],
    context: Option[String]): SearchFilter = {
    filter match {
      case Some("m") =>
        SearchFilter.mine(library, context)
      case Some("f") =>
        SearchFilter.friends(library, context)
      case Some("a") =>
        SearchFilter.all(library, context)
      case _ =>
        SearchFilter.default(library, context)
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

class SearchCommanderBackwardCompatibilitySupport(
    shards: ActiveShards,
    searchFactory: SearchFactory,
    mainSearcherFactory: MainSearcherFactory) {

  import com.keepit.search.graph.BookmarkInfoAccessor

  def toPartialSearchResult(userId: Id[User], friendIds: Set[Long], result: KifiShardResult): PartialSearchResult = {

    val hits = result.hits

    def toDetailedSearchHit(h: KifiShardHit, friendStats: FriendStats): DetailedSearchHit = {
      val uriId = Id[NormalizedURI](h.id)
      val isMyBookmark = ((h.visibility & Visibility.OWNER) != 0)
      val isFriendsBookmark = (!isMyBookmark && (h.visibility & Visibility.NETWORK) != 0)

      shards.find(uriId) match {
        case Some(shard) =>
          val uriGraphSearcher = mainSearcherFactory.getURIGraphSearcher(shard, userId)
          val collectionSearcher = mainSearcherFactory.getCollectionSearcher(shard, userId)

          val sharingInfo = uriGraphSearcher.getSharingUserInfo(uriId)
          val myUriEdgeAccessor = uriGraphSearcher.myUriEdgeSet.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]]

          val isPrivate = (isMyBookmark && myUriEdgeAccessor.seek(h.id) && !myUriEdgeAccessor.isPublic)

          val basicSearchHit = if (isMyBookmark) {
            val collections = {
              val collIds = collectionSearcher.intersect(collectionSearcher.myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(uriId)).destIdLongSet
              if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map { id => collectionSearcher.getExternalId(id) }.collect { case Some(extId) => extId })
            }
            BasicSearchHit(Some(h.title), h.url, collections, h.keepId)
          } else {
            BasicSearchHit(Some(h.title), h.url)
          }

          val sharingUserIds = sharingInfo.sharingUserIds.toSeq
          val score = h.score
          sharingUserIds.foreach { friendId => friendStats.add(friendId.id, score) }

          DetailedSearchHit(
            uriId.id,
            sharingInfo.keepersEdgeSetSize,
            basicSearchHit,
            isMyBookmark,
            isFriendsBookmark,
            isPrivate,
            sharingUserIds,
            score,
            new Scoring(score, 0.0f, 0.0f, 0.0f, false)
          )

        case None =>
          throw new Exception("shard not found")
      }
    }

    val friendStats = FriendStats(friendIds)

    val detailedSearchHits = hits.map { h =>
      toDetailedSearchHit(h, friendStats)
    }

    PartialSearchResult(detailedSearchHits, result.myTotal, result.friendsTotal, result.othersTotal, friendStats, result.show)
  }
}
