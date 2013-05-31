package com.keepit.controllers.ext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.future
import scala.concurrent.Future
import scala.util.Try
import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.performance._
import com.keepit.common.social.BasicUser
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

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(id: Id[NormalizedURI], externalId: ExternalId[NormalizedURI], title: Option[String], url: String, isPrivate: Boolean)
object PersonalSearchHit {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'id).format(Id.format[NormalizedURI]) and
    (__ \ 'externalId).format(ExternalId.format[NormalizedURI]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'isPrivate).format[Boolean]
  )(PersonalSearchHit.apply, unlift(PersonalSearchHit.unapply))
}

case class PersonalSearchResult(hit: PersonalSearchHit, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[BasicUser], score: Float, isNew: Boolean)
case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  show: Boolean,
  experimentId: Option[Id[SearchConfigExperiment]],
  context: String)

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
             tz: Option[String] = None) = AuthenticatedJsonAction { request =>

    val t1 = currentDateTime.getMillis()

    val lastUUID = lastUUIDStr.flatMap{
      case "" => None
      case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val userId = request.userId
    log.info(s"""User ${userId} searched ${query.length} characters""")

    val searchFilter = filter match {
      case Some("m") =>
        SearchFilter.mine(context, None, start, end, tz)
      case Some("f") =>
        SearchFilter.friends(context, start, end, tz)
      case Some(ids) =>
        val userExtIds = ids.split('.').flatMap(id => Try(ExternalId[User](id)).toOption)
        val idFuture = shoeboxClient.getUserIdsByExternalIds(userExtIds)
        SearchFilter.custom(context, idFuture, start, end, tz, monitoredAwait)
      case None =>
        if (start.isDefined || end.isDefined) SearchFilter.all(context, start, end, tz)
        else SearchFilter.default(context)
    }

    val (config, experimentId) = searchConfigManager.getConfig(userId, query)

    val t2 = currentDateTime.getMillis()
    var t3 = 0L
    val searchRes = time("search-searching") {
      val searcher = time("search-factory") { mainSearcherFactory(userId, searchFilter, config) }
      t3 = currentDateTime.getMillis()
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

    val res = toPersonalSearchResultPacket(userId, searchRes, config, searchFilter.isDefault, experimentId)
    reportArticleSearchResult(searchRes)

    val t5 = currentDateTime.getMillis()
    val total = t5 - t1
    log.info(s"total search time = $total, pre-search time = ${t2 - t1}, search-factory time = ${t3 - t2}, main-search time = ${t4 - t3}, post-search time = ${t5 - t4}")
    val searchDetails = searchRes.timeLogs match {
      case Some(timelog) => "main-search detail: " + timelog.toString
      case None => "main-search detail: N/A"
    }
    log.info(searchDetails)

    try{
      NewRelic.setTransactionName(null, "search")
      NewRelic.incrementCounter("Custom/search")
      NewRelic.recordResponseTimeMetric("Custom/SearchTotal", total)
    } catch {
      case e: Exception => log.warn("error in adding record to newRelic")
    }

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

  private[ext] def toPersonalSearchResultPacket(userId: Id[User],
      res: ArticleSearchResult, config: SearchConfig, isDefaultFilter: Boolean, experimentId: Option[Id[SearchConfigExperiment]]): PersonalSearchResultPacket = {

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    val hitsFuture = time(s"search-personal-result-${res.hits.size}") {
      toPersonalSearchResult(userId, res).map{r => log.debug(r.mkString("\n")); r}
    }

    val hits = monitoredAwait.result(hitsFuture, 5 seconds, Nil)

    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, (!isDefaultFilter || res.toShow), experimentId, filter)
  }

  private[ext] def toPersonalSearchResult(userId: Id[User], resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]] = {
    shoeboxClient.getPersonalSearchInfo(userId, resultSet).map { case (allUsers, personalSearchHits) =>
      (resultSet.hits, resultSet.scorings, personalSearchHits).zipped.toSeq.map { case (hit, score, personalHit) =>
        val users = hit.users.map(allUsers)
        val isNew = (!hit.isMyBookmark && score.recencyScore > 0.5f)
        PersonalSearchResult(personalHit,
          hit.bookmarkCount,
          hit.isMyBookmark,
          personalHit.isPrivate,
          users,
          hit.score,
          isNew)
      }
    }
  }

}
