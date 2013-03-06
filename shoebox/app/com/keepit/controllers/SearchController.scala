package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes

import play.api.Play.current
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.sql.Connection
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController

import scala.util.Try
import views.html

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(id: Id[NormalizedURI], externalId: ExternalId[NormalizedURI], title: Option[String], url: String)
case class PersonalSearchResult(hit: PersonalSearchHit, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[UserWithSocial], score: Float)
case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  experimentId: Option[Id[SearchConfigExperiment]],
  context: String)

object SearchController extends FortyTwoController {

  def search(query: String, filter: Option[String], maxHits: Int, lastUUIDStr: Option[String], context: Option[String], kifiVersion: Option[KifiVersion] = None) = AuthenticatedJsonAction { request =>
    val lastUUID = lastUUIDStr.flatMap{
      case "" => None
      case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val userId = request.userId
    log.info("searching with %s using userId id %s".format(query, userId))
    val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
    val (friendIds, searchFilter) = inject[Database].readOnly { implicit s =>
      val friendIds = inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)
      val searchFilter = filter match {
        case Some("m") =>
          SearchFilter.mine(idFilter)
        case Some("f") =>
          SearchFilter.friends(idFilter)
        case Some(ids) =>
          val userRepo = inject[UserRepo]
          val userIds = ids.split('.').flatMap(id => Try(ExternalId[User](id)).toOption).flatMap(userRepo.getOpt(_)).flatMap(_.id)
          SearchFilter.custom(idFilter, userIds.toSet)
        case None =>
          SearchFilter.default(idFilter)
      }
      (friendIds, searchFilter)
    }

    val (config, experimentId) = inject[SearchConfigManager].getConfig(userId, query)

    val mainSearcherFactory = inject[MainSearcherFactory]
    val searcher = mainSearcherFactory(userId, friendIds, searchFilter, config)
    val searchRes = if (maxHits > 0) {
      searcher.search(query, maxHits, lastUUID, searchFilter)
    } else {
      log.warn("maxHits is zero")
      ArticleSearchResult(lastUUID, query, Seq.empty[ArticleHit], 0, 0, true, Seq.empty[Scoring], idFilter, 0, Int.MaxValue)
    }
    val res = toPersonalSearchResultPacket(userId, searchRes, config, experimentId)
    reportArticleSearchResult(searchRes)
    Ok(RPS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }

  private def reportArticleSearchResult(res: ArticleSearchResult) {
    dispatch ({
      inject[Database].readWrite { implicit s =>
        inject[ArticleSearchResultRefRepo].save(ArticleSearchResultFactory(res))
      }
      inject[ArticleSearchResultStore] += (res.uuid -> res)
    }, { e =>
      log.error("Could not persist article search result %s".format(res), e)
    })
  }

  private[controllers] def toPersonalSearchResultPacket(userId: Id[User],
      res: ArticleSearchResult, config: SearchConfig, experimentId: Option[Id[SearchConfigExperiment]]): PersonalSearchResultPacket = {
    val hits = inject[Database].readOnly { implicit s =>
      res.hits.map(toPersonalSearchResult(userId, _))
    }
    log.debug(hits mkString "\n")

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, experimentId, filter)
  }

  private[controllers] def toPersonalSearchResult(userId: Id[User], res: ArticleHit)(implicit session: RSession): PersonalSearchResult = {
    val uri = inject[NormalizedURIRepo].get(res.uriId)
    val bookmark = if (res.isMyBookmark) inject[BookmarkRepo].getByUriAndUser(uri.id.get, userId) else None
    val users = res.users.toSeq.map{ userId =>
      val user = inject[UserRepo].get(userId)
      val info = inject[SocialUserInfoRepo].getByUser(userId).head
      UserWithSocial(user, info, inject[BookmarkRepo].count(userId), Nil, Nil)
    }
    PersonalSearchResult(
      toPersonalSearchHit(uri, bookmark), res.bookmarkCount, res.isMyBookmark,
      bookmark.map(_.isPrivate).getOrElse(false), users, res.score)
  }

  private[controllers] def toPersonalSearchHit(uri: NormalizedURI, bookmark: Option[Bookmark]) = {
    val (title, url) = bookmark match {
      case Some(bookmark) => (Some(bookmark.title), bookmark.url)
      case None => (uri.title, uri.url)
    }

    PersonalSearchHit(uri.id.get, uri.externalId, title, url)
  }

  def explain(queryString: String, uriId: Id[NormalizedURI]) = AdminHtmlAction { request =>
    val userId = request.userId
    val friendIds = inject[Database].readOnly { implicit s =>
      inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)
    }
    val (config, _) = inject[SearchConfigManager].getConfig(userId, queryString)

    val mainSearcherFactory = inject[MainSearcherFactory]
    val searcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), config)
    val explanation = searcher.explain(queryString, uriId)

    Ok(html.admin.explainResult(queryString, userId, uriId, explanation))
  }

  case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

  def articleSearchResult(id: ExternalId[ArticleSearchResultRef]) = AdminHtmlAction { implicit request =>
    val ref = inject[Database].readWrite { implicit s =>
      inject[ArticleSearchResultRefRepo].get(id)
    }
    val result = inject[ArticleSearchResultStore].get(ref.externalId).get
    val uriRepo = inject[NormalizedURIRepo]
    val userRepo = inject[UserRepo]
    val metas: Seq[ArticleSearchResultHitMeta] = inject[Database].readOnly { implicit s =>
      result.hits.zip(result.scorings) map { tuple =>
        val hit = tuple._1
        val scoring = tuple._2
        val uri = uriRepo.get(hit.uriId)
        val users = hit.users.map { userId =>
          userRepo.get(userId)
        }
        ArticleSearchResultHitMeta(uri, users.toSeq, scoring, hit)
      }
    }
    Ok(html.admin.articleSearchResult(result, metas))
  }
}
