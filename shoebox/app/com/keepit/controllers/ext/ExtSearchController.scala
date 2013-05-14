package com.keepit.controllers.ext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.future
import scala.util.Try
import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.performance._
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.search._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import play.api.http.ContentTypes
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError}
import com.keepit.common.healthcheck.Healthcheck.SEARCH


//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(id: Id[NormalizedURI], externalId: ExternalId[NormalizedURI], title: Option[String], url: String)
case class PersonalSearchResult(hit: PersonalSearchHit, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[BasicUser], score: Float)
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
  db: Database,
  userRepo: UserRepo,
  userConnectionRepo: UserConnectionRepo,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  articleSearchResultRefRepo: ArticleSearchResultRefRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  basicUserRepo: BasicUserRepo,
  srcFactory: SearchResultClassifierFactory,
  healthcheckPlugin: HealthcheckPlugin)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging{

  def search(query: String, filter: Option[String], maxHits: Int, lastUUIDStr: Option[String], context: Option[String], kifiVersion: Option[KifiVersion] = None) = AuthenticatedJsonAction { request =>

    val t1 = currentDateTime.getMillis()

    val lastUUID = lastUUIDStr.flatMap{
      case "" => None
      case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val userId = request.userId
    log.info(s"""User ${userId} searched ${query.length} characters""")
    val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
    val (friendIds, searchFilter) = time("search-connections") {
      db.readOnly { implicit s =>
        val friendIds = userConnectionRepo.getConnectedUsers(userId)
        val searchFilter = filter match {
          case Some("m") =>
            SearchFilter.mine(idFilter)
          case Some("f") =>
            SearchFilter.friends(idFilter)
          case Some(ids) =>
            val userIds = ids.split('.').flatMap(id => Try(ExternalId[User](id)).toOption).flatMap(userRepo.getOpt(_)).flatMap(_.id)
            SearchFilter.custom(idFilter, userIds.toSet)
          case None =>
            SearchFilter.default(idFilter)
        }
        (friendIds, searchFilter)
      }
    }

    val (config, experimentId) = searchConfigManager.getConfig(userId, query)

    val t2 = currentDateTime.getMillis()
    var t3 = 0L
    val searchRes = time("search-searching") {
      val searcher = time("search-factory") { mainSearcherFactory(userId, friendIds, searchFilter, config) }
      t3 = currentDateTime.getMillis()
      val searchRes = if (maxHits > 0) {
        searcher.search(query, maxHits, lastUUID, searchFilter)
      } else {
        log.warn("maxHits is zero")
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
    val timeLimit = 1000
    // search is a little slow after service restart. allow some grace period
    if (total > timeLimit && t5 - fortyTwoServices.started.getMillis() > 1000*60*8) {
      val link = "https://admin.kifi.com/admin/search/results/" + searchRes.uuid.id
      val msg = s"search time exceeds limit! searchUUID = ${searchRes.uuid.id}, Limit time = $timeLimit, total search time = $total, pre-search time = ${t2 - t1}, search-factory time = ${t3 - t2}, main-search time = ${t4 - t3}, post-search time = ${t5 - t4}." +
      		"More details at ${link}"
      healthcheckPlugin.addError(HealthcheckError(
        error = Some(new SearchTimeExceedsLimit(timeLimit, total)),
        errorMessage = Some(msg),
        callType = SEARCH))
    }

    Ok(RPS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }

  class SearchTimeExceedsLimit(timeout: Int, actual: Long) extends Exception(s"Timeout ${timeout}ms, actual ${actual}ms")

  private def reportArticleSearchResult(res: ArticleSearchResult) {
    future {
      db.readWrite { implicit s =>
        articleSearchResultRefRepo.save(ArticleSearchResultFactory(res))
      }
      articleSearchResultStore += (res.uuid -> res)
    } onFailure { case e =>
      log.error("Could not persist article search result %s".format(res), e)
    }
  }

  private[ext] def toPersonalSearchResultPacket(userId: Id[User],
      res: ArticleSearchResult, config: SearchConfig, isDefaultFilter: Boolean, experimentId: Option[Id[SearchConfigExperiment]]): PersonalSearchResultPacket = {


    val hits = time(s"search-personal-result-${res.hits.size}") {
      db.readOnly { implicit s =>
        val t0 = currentDateTime.getMillis()
        val users = res.hits.map(_.users).flatten.distinct.map(u => u -> basicUserRepo.load(u)).toMap
        log.info(s"search-personal-a: ${currentDateTime.getMillis()-t0}")
        val t1 = currentDateTime.getMillis()
        val h = res.hits.map(toPersonalSearchResult(userId, users, _))
        log.info(s"search-personal-d: ${currentDateTime.getMillis()-t1}")
        h
      }
    }
    log.debug(hits mkString "\n")

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, (!isDefaultFilter || res.toShow), experimentId, filter)
  }

  private[ext] def toPersonalSearchResult(userId: Id[User], allUsers: Map[Id[User], BasicUser], res: ArticleHit)(implicit session: RSession): PersonalSearchResult = {
    val t0 = currentDateTime.getMillis()
    val uri = uriRepo.get(res.uriId)
    log.info(s"search-personal-b: ${currentDateTime.getMillis()-t0}")
    val t1 = currentDateTime.getMillis()
    val bookmark = if (res.isMyBookmark) bookmarkRepo.getByUriAndUser(uri.id.get, userId) else None
    log.info(s"search-personal-c: ${currentDateTime.getMillis()-t1}")
    val users = res.users.map(allUsers)
    PersonalSearchResult(
      toPersonalSearchHit(uri, bookmark), res.bookmarkCount, res.isMyBookmark,
      bookmark.map(_.isPrivate).getOrElse(false), users, res.score)
  }

  private[ext] def toPersonalSearchHit(uri: NormalizedURI, bookmark: Option[Bookmark]) = {
    val (title, url) = bookmark match {
      case Some(bookmark) => (bookmark.title, bookmark.url)
      case None => (uri.title, uri.url)
    }

    PersonalSearchHit(uri.id.get, uri.externalId, title, url)
  }

}
