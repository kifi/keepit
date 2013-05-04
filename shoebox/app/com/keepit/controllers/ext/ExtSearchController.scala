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
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck.INTERNAL


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

    val searchRes = time("search-searching") {
      val searcher = mainSearcherFactory(userId, friendIds, searchFilter, config)
      val searchRes = if (maxHits > 0) {
        searcher.search(query, maxHits, lastUUID, searchFilter)
      } else {
        log.warn("maxHits is zero")
        ArticleSearchResult(lastUUID, query, Seq.empty[ArticleHit], 0, 0, true, Seq.empty[Scoring], idFilter, 0, Int.MaxValue)
      }

      searchRes
    }

    val t3 = currentDateTime.getMillis()

    val res = toPersonalSearchResultPacket(userId, searchRes, config, searchFilter.isDefault, experimentId)
    reportArticleSearchResult(searchRes)

    val t4 = currentDateTime.getMillis()

    val timeLimit = 1000
    if (t4 - t1 > timeLimit && t4 - fortyTwoServices.started.getMillis() > 1000*60*5) {
      val msg = "search time exceeds limit! searchUUID = %s , Limit time = %d, total search time = %d, pre-search time = %d, main-search time = %d, post-search time = %d".format(searchRes.uuid.id, timeLimit, t4 - t1, t2 - t1, t3 - t2, t4 - t3)
      healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(msg), callType = INTERNAL))
    }

    Ok(RPS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }

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

    val doParallel = util.Random.nextBoolean

    val hits = if(doParallel) {
      time(s"search-personal-result-parallel-${res.hits.size}") {
        res.hits.par.map { hit =>
          db.readOnly { implicit s =>
            toPersonalSearchResult(userId, hit)
          }
        } seq
      }
    } else {
      time(s"search-personal-result-${res.hits.size}") {
        db.readOnly { implicit s =>
          res.hits.map(toPersonalSearchResult(userId, _))
        }
      }
    }
    log.debug(hits mkString "\n")

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, (!isDefaultFilter || res.toShow), experimentId, filter)
  }

  private[ext] def toPersonalSearchResult(userId: Id[User], res: ArticleHit)(implicit session: RSession): PersonalSearchResult = {
    val uri = uriRepo.get(res.uriId)
    val bookmark = if (res.isMyBookmark) bookmarkRepo.getByUriAndUser(uri.id.get, userId) else None
    val users = res.users.toSeq.map(basicUserRepo.load)
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
