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
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.common.db.ExternalId
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api.http.ContentTypes
import play.api.libs.json.JsString
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.common.social.UserWithSocial
import com.keepit.search.MainSearcher
import com.keepit.search.SearchConfig
import com.keepit.search.graph.URIGraph
import com.keepit.search.ArticleHit

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchResult(uri: NormalizedURI, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[UserWithSocial], score: Float)

object SearchController extends Controller with Logging {
 
  def search(term: String, externalId: ExternalId[User]) = Action { request =>
    println("searching with %s using externalId id %s".format(term, externalId))
    val (userId, friendIds) = CX.withConnection { implicit conn =>
      val userId = User.getOpt(externalId).getOrElse(
          throw new Exception("externalId %s not found for term %s".format(externalId, term))).id.get
      (userId, SocialConnection.getFortyTwoUserConnections(userId))
    }
    
    // TODO turn these into a parameter
    val filterOut = Set.empty[Long]
    val numHitsToReturn = 6
    val config = SearchConfig.getDefaultConfig 
    
    val articleIndexer = inject[ArticleIndexer]
    val uriGraph = inject[URIGraph]
    val searcher = new MainSearcher(articleIndexer, uriGraph, config)
    val searchRes = searcher.search(term, userId, friendIds, filterOut, numHitsToReturn)
    val res = CX.withConnection { implicit conn =>
      searchRes map { r => toPersonalSearchResult(r) }
    }
    println(res mkString "\n")
    Ok(BPSRS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  private[controllers] def toPersonalSearchResult(res: ArticleHit)(implicit conn: Connection): PersonalSearchResult = {
    val uri = NormalizedURI.get(res.uriId)
    val count = uri.bookmarks().size
    val users = res.users.toSeq.map{ userId =>
      val user = User.get(userId) 
      val info = SocialUserInfo.getByUser(user.id.get).head
      UserWithSocial(user, info)
    }
    PersonalSearchResult(uri, count, res.isMyBookmark, false, users, res.score)
  }
  
}