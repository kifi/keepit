package com.keepit.controllers.ext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.future
import scala.concurrent.Future
import scala.util.Try
import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.model.ExperimentTypes.NO_SEARCH_EXPERIMENTS
import com.keepit.search._
import com.keepit.serializer.PersonalSearchResultPacketSerializer.resSerializer
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError}
import com.keepit.common.healthcheck.Healthcheck.SEARCH
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import play.api.libs.json.Json
import com.keepit.common.db.{ExternalId, Id}
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.Trace
import play.modules.statsd.api.Statsd
import com.keepit.social.BasicUser
import scala.concurrent.Promise

@Singleton
class ExtSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  healthcheckPlugin: HealthcheckPlugin,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging{

  @Trace
  def search(query: String,
             filter: Option[String],
             maxHits: Int,
             lastUUIDStr: Option[String],
             context: Option[String],
             kifiVersion: Option[KifiVersion] = None,
             start: Option[String] = None,
             end: Option[String] = None,
             tz: Option[String] = None,
             coll: Option[String] = None) = AuthenticatedJsonAction { request =>

    val t1 = currentDateTime.getMillis()

    val userId = request.userId
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

    val (config, searchExperimentId) = searchConfigManager.getConfig(userId, query, request.experiments.contains(NO_SEARCH_EXPERIMENTS))

    val lastUUID = lastUUIDStr.flatMap{
      case "" => None
      case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val t2 = currentDateTime.getMillis()

    val probabilities = getLangsPriorProbabilities(request.request.acceptLanguages.map(_.code))
    val searcher = timeWithStatsd("search-factory", "extSearch.factory") {
      mainSearcherFactory(userId, query, probabilities, maxHits, searchFilter, config, lastUUID)
    }

    val t3 = currentDateTime.getMillis()

    val searchRes = timeWithStatsd("search-searching", "extSearch.searching") {
      val searchRes = if (maxHits > 0) {
        searcher.search()
      } else {
        log.warn("maxHits is zero")
        val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
        ArticleSearchResult(lastUUID, query, Seq.empty[ArticleHit], 0, 0, true, Seq.empty[Scoring], idFilter, 0, Int.MaxValue)
      }

      searchRes
    }

    val experts = if (filter.isEmpty && config.asBoolean("showExperts")) {
      suggestExperts(searchRes)
    } else { Promise.successful(List.empty[Id[User]]).future }

    val t4 = currentDateTime.getMillis()

    val decorator = ResultDecorator(searcher, shoeboxClient, config)
    val res = toPersonalSearchResultPacket(decorator, userId, searchRes, config, searchFilter.isDefault, searchExperimentId, experts)
    reportArticleSearchResult(searchRes)

    val t5 = currentDateTime.getMillis()
    val total = t5 - t1

    Statsd.timing("extSearch.postSearchTime", t5 - t4)
    Statsd.timing("extSearch.total", total)
    Statsd.increment("extSearch.total")

    log.info(s"total search time = $total, pre-search time = ${t2 - t1}, search-factory time = ${t3 - t2}, main-search time = ${t4 - t3}, post-search time = ${t5 - t4}")
    val searchDetails = searchRes.timeLogs match {
      case Some(timelog) => "main-search detail: " + timelog.toString
      case None => "main-search detail: N/A"
    }
    log.info(searchDetails)

    val timeLimit = 1000
    // search is a little slow after service restart. allow some grace period
    if (total > timeLimit && t5 - fortyTwoServices.started.getMillis() > 1000*60*8) {
      val link = "https://admin.kifi.com/admin/search/results/" + searchRes.uuid.id
      val msg = s"search time exceeds limit! searchUUID = ${searchRes.uuid.id}, Limit time = $timeLimit, total search time = $total, pre-search time = ${t2 - t1}, search-factory time = ${t3 - t2}, main-search time = ${t4 - t3}, post-search time = ${t5 - t4}." +
        "\n More details at: \n" + link + "\n" + searchDetails + "\n"
      healthcheckPlugin.addError(HealthcheckError(
        error = Some(new SearchTimeExceedsLimit(timeLimit, total)),
        errorMessage = Some(msg),
        callType = SEARCH))
    }

    Ok(Json.toJson(res)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  private def getLangsPriorProbabilities(acceptLangs: Seq[String]): Map[Lang, Double] = {
    val langs = acceptLangs.toSet.flatMap{ code: String =>
      println(s"accept-langauge ===>>> $code")
      val lang = code.substring(0,2)
      if (lang == "zh") Set("zh-cn", "zh-tw") else Set(lang)
    } + "en" // always include English

    val size = langs.size
    if (size == 0) {
      Map(Lang("en") -> 0.9d)
    } else {
      val prob = (1.0d - 0.1d / size) / size
      langs.map{ (Lang(_) -> prob) }.toMap
    }
  }

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

  private def reportArticleSearchResult(res: ArticleSearchResult) {
    future {
      shoeboxClient.reportArticleSearchResult(res)
      articleSearchResultStore += (res.uuid -> res)
    } onFailure { case e =>
      log.error("Could not persist article search result %s".format(res), e)
    }
  }

  private[ext] def toPersonalSearchResultPacket(decorator: ResultDecorator, userId: Id[User],
      res: ArticleSearchResult,
      config: SearchConfig,
      isDefaultFilter: Boolean,
      searchExperimentId: Option[Id[SearchConfigExperiment]],
      expertsFuture: Future[Seq[Id[User]]]): PersonalSearchResultPacket = {

    val future = decorator.decorate(res)
    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    val experts = monitoredAwait.result(expertsFuture, 50 milliseconds, s"suggesting experts", List.empty[Id[User]]).filter(_.id != userId.id)
    val expertNames = {
      if (experts.size == 0) List.empty[String]
      else {
        val idMap = monitoredAwait.result(shoeboxClient.getBasicUsers(experts), 50 milliseconds, s"getting experts' external ids", Map.empty[Id[User], BasicUser])
        experts.flatMap{idMap.get(_)}.map{x => x.firstName + " " + x.lastName}
      }
    }


    PersonalSearchResultPacket(res.uuid, res.query,
      monitoredAwait.result(future, 5 seconds, s"getting search decorations for $userId", Nil),
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

}
