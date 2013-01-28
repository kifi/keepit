package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
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
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchHit(id: Id[NormalizedURI], externalId: ExternalId[NormalizedURI], title: Option[String], url: String)
case class PersonalSearchResult(hit: PersonalSearchHit, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[UserWithSocial], score: Float)
case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  context: String)

object SearchController extends FortyTwoController {

  def search(escapedTerm: String, maxHits: Int, lastUUIDStr: Option[String], filter: Option[String], context: Option[String], kifiVersion: Option[KifiVersion] = None) = AuthenticatedJsonAction { request =>
    val term = StringEscapeUtils.unescapeHtml4(escapedTerm)
    val lastUUID = lastUUIDStr.flatMap{
        case "" => None
        case str => Some(ExternalId[ArticleSearchResultRef](str))
    }
    val searchFilter = SearchFilter(filter)

    val userId = request.userId
    log.info("searching with %s using userId id %s".format(term, userId))
    val friendIds = inject[DBConnection].readOnly { implicit s =>
      inject[SocialConnectionRepo].getFortyTwoUserConnections(userId)
    }

    val filterOut = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
    val config = inject[SearchConfigManager].getUserConfig(userId)

    val articleIndexer = inject[ArticleIndexer]
    val uriGraph = inject[URIGraph]
    val resultClickTracker = inject[ResultClickTracker]
    val browsingHistoryTracker = inject[BrowsingHistoryTracker]
    val searcher = new MainSearcher(userId, friendIds, filterOut, articleIndexer, uriGraph, resultClickTracker, browsingHistoryTracker, config)
    val searchRes = searcher.search(term, maxHits, lastUUID, searchFilter)
    val realResults = toPersonalSearchResultPacket(userId, searchRes)

    val res = if (kifiVersion.getOrElse(KifiVersion(0,0,0)) >= KifiVersion(2,0,8)) {
      realResults
    } else {
      val upgradeResult = PersonalSearchResult(
        hit = PersonalSearchHit(id = Id[NormalizedURI](0), externalId = ExternalId[NormalizedURI](), title = Some("★★★ KiFi has updated! Please reload your plugin. ★★★"), url = "http://keepitfindit.com/upgrade"),
        count = 0,
        isMyBookmark = false,
        isPrivate = false,
        users = Nil,
        score = 42f)
      realResults.copy(hits = Seq(upgradeResult) ++ realResults.hits)
    }

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

  private[controllers] def toPersonalSearchResultPacket(userId: Id[User], res: ArticleSearchResult) = {
    val hits = CX.withConnection { implicit conn =>
      res.hits.map(toPersonalSearchResult(userId, _))
    }
    log.debug(hits mkString "\n")

    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, filter)
  }
  private[controllers] def toPersonalSearchResult(userId: Id[User], res: ArticleHit)(implicit conn: Connection): PersonalSearchResult = {
    val uri = NormalizedURICxRepo.get(res.uriId)
    val bookmark = if (res.isMyBookmark) BookmarkCxRepo.getByUriAndUser(uri.id.get, userId) else None
    val users = res.users.toSeq.map{ userId =>
      val user = UserCxRepo.get(userId)
      val info = SocialUserInfoCxRepo.getByUser(user.id.get).head
      UserWithSocial(user, info, BookmarkCxRepo.count(user).toInt, Seq(), Seq())
    }
    PersonalSearchResult(toPersonalSearchHit(uri, bookmark), res.bookmarkCount, res.isMyBookmark, false, users, res.score)
  }
  private[controllers] def toPersonalSearchHit(uri: NormalizedURI, bookmark: Option[Bookmark]) = {
    val (title, url) = bookmark match {
      case Some(bookmark) => (Some(bookmark.title), bookmark.url)
      case None => (uri.title, uri.url)
    }

    PersonalSearchHit(uri.id.get, uri.externalId, title, url)
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
        val uri = NormalizedURICxRepo.get(hit.uriId)
        val users = hit.users.map { userId =>
          UserCxRepo.get(userId)
        }
        ArticleSearchResultHitMeta(uri, users.toSeq, scoring, hit)
      }
    }
    Ok(views.html.articleSearchResult(result, metas))
  }

}
