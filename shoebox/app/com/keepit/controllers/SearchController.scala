package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import play.api.libs.json.JsArray
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.db.ExternalId
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api.http.ContentTypes
import play.api.libs.json.JsString
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.common.actor.ActorPlugin
import com.keepit.search.ArticleSearchResultStore
import securesocial.core._
import com.keepit.common.controller.FortyTwoController

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchResult(uri: NormalizedURI, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[UserWithSocial], score: Float)

case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  context: String)

object SearchController extends FortyTwoController {

  def search2(escapedTerm: String, maxHits: Int, lastUUIDStr: Option[String], context: Option[String]) = search(escapedTerm, maxHits, lastUUIDStr, context)

  def search(escapedTerm: String, maxHits: Int, lastUUIDStr: Option[String], context: Option[String]) = AuthenticatedJsonAction { request =>
    val term = StringEscapeUtils.unescapeHtml4(escapedTerm)
    val lastUUID = lastUUIDStr.flatMap{
        case "" => None
        case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    val userId = request.userId
    log.info("searching with %s using userId id %s".format(term, userId))
    val friendIds = CX.withConnection { implicit conn =>
      SocialConnection.getFortyTwoUserConnections(userId)
    }

    val filterOut = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
    val config = SearchConfig.getDefaultConfig

    val articleIndexer = inject[ArticleIndexer]
    val uriGraph = inject[URIGraph]
    val searcher = new MainSearcher(userId, friendIds, filterOut, articleIndexer, uriGraph, config)
    val searchRes = searcher.search(term, maxHits, lastUUID)
    val res = toPersonalSearchResultPacket(searchRes)
    reportArticleSearchResult(searchRes)
    Ok(RPS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }

  private def reportArticleSearchResult(res: ArticleSearchResult) = dispatch ({
        CX.withConnection { implicit c =>
          ArticleSearchResultRef(res).save
        }
        inject[ArticleSearchResultStore] += (res.uuid -> res)
      }, { e =>
         log.error("Could not persist article search result %s".format(res), e)
      })

  private[controllers] def toPersonalSearchResultPacket(res: ArticleSearchResult) = {
    val hits = CX.withConnection { implicit conn =>
      res.hits map toPersonalSearchResult
    }
    log.info(hits mkString "\n")

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, filter)
  }
  private[controllers] def toPersonalSearchResult(res: ArticleHit)(implicit conn: Connection): PersonalSearchResult = {
    val uri = NormalizedURI.get(res.uriId)
    val users = res.users.toSeq.map{ userId =>
      val user = User.get(userId)
      val info = SocialUserInfo.getByUser(user.id.get).head
      UserWithSocial(user, info, Bookmark.count(user), Seq(), Seq())
    }
    PersonalSearchResult(uri, res.bookmarkCount, res.isMyBookmark, false, users, res.score)
  }

  case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

  def articleSearchResult(id: ExternalId[ArticleSearchResultRef]) = AdminHtmlAction { implicit request =>
    val ref = CX.withConnection { implicit conn =>
      ArticleSearchResultRef.getOpt(id).get
    }
    val result = inject[ArticleSearchResultStore].get(ref.externalId).get
    val metas: Seq[ArticleSearchResultHitMeta] = CX.withConnection { implicit conn =>
      result.hits.zip(result.scorings) map { tuple =>
        val hit = tuple._1
        val scoring = tuple._2
        val uri = NormalizedURI.get(hit.uriId)
        val users = hit.users.map { userId =>
          User.get(userId)
        }
        ArticleSearchResultHitMeta(uri, users.toSeq, scoring, hit)
      }
    }
    Ok(views.html.articleSearchResult(result, metas))
  }

}
