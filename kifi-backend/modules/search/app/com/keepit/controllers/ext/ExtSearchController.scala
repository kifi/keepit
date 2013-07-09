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

@Singleton
class ExtSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  srcFactory: SearchResultClassifierFactory,
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

    val (config, experimentId) = searchConfigManager.getConfig(userId, query)

    val lastUUID = lastUUIDStr.flatMap{
      case "" => None
      case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val t2 = currentDateTime.getMillis()
    var t3 = 0L
    val searcher = timeWithStatsd("search-factory", "extSearch.factory") { mainSearcherFactory(userId, searchFilter, config) }
    t3 = currentDateTime.getMillis()
    val searchRes = timeWithStatsd("search-searching", "extSearch.searching") {
      val searchRes = if (maxHits > 0) {
        searcher.search(query, maxHits, lastUUID, searchFilter)
      } else {
        log.warn("maxHits is zero")
        val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
        ArticleSearchResult(lastUUID, query, Seq.empty[ArticleHit], 0, 0, true, Seq.empty[Scoring], idFilter, 0, Int.MaxValue)
      }

      searchRes
    }

    val t4 = currentDateTime.getMillis()

    val decorator = ResultDecorator(searcher, shoeboxClient, config)
    val res = toPersonalSearchResultPacket(decorator, userId, searchRes, config, searchFilter.isDefault, experimentId)

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
      res: ArticleSearchResult, config: SearchConfig, isDefaultFilter: Boolean, experimentId: Option[Id[SearchConfigExperiment]]): PersonalSearchResultPacket = {

    val future = decorator.decorate(res)
    val filter = IdFilterCompressor.fromSetToBase64(res.filter)

    PersonalSearchResultPacket(res.uuid, res.query,
      monitoredAwait.result(future, 5 seconds, s"getting search decorations for $userId", Nil),
      res.mayHaveMoreHits, (!isDefaultFilter || res.toShow), experimentId, filter)
  }

}
