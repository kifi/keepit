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
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
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
import com.keepit.search.ArticleSearchResult
import com.keepit.search.ArticleSearchResultRef
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.search.IdFilterCompressor

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchResult(uri: NormalizedURI, count: Int, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[UserWithSocial], score: Float)

case class PersonalSearchResultPacket(
  uuid: ExternalId[ArticleSearchResultRef],
  query: String,
  hits: Seq[PersonalSearchResult],
  mayHaveMoreHits: Boolean,
  context: String)

object SearchController extends Controller with Logging {
 
  def search(escapedTerm: String, externalId: ExternalId[User]) = Action { request =>
    val term = StringEscapeUtils.unescapeHtml4(escapedTerm)
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
    val searcher = new MainSearcher(userId, friendIds, filterOut, articleIndexer, uriGraph, config)
    val searchRes = searcher.search(term, numHitsToReturn, None) // the last param should be the uuid of the last search
    val res = CX.withConnection { implicit conn =>
      searchRes.hits map toPersonalSearchResult
    }
    println(res mkString "\n")
    Ok(BPSRS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  def search2(escapedTerm: String, externalId: ExternalId[User], maxHits: Int, lastUUIDStr: Option[String], context: Option[String]) = Action { request =>
    val term = StringEscapeUtils.unescapeHtml4(escapedTerm)
    val lastUUID = lastUUIDStr.flatMap{
        case "" => None
        case str => Some(ExternalId[ArticleSearchResultRef](str))
    }

    println("searching with %s using externalId id %s".format(term, externalId))
    val (userId, friendIds) = CX.withConnection { implicit conn =>
      val userId = User.getOpt(externalId).getOrElse(
          throw new Exception("externalId %s not found for term %s".format(externalId, term))).id.get
      (userId, SocialConnection.getFortyTwoUserConnections(userId))
    }
    
    val filterOut = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
    val config = SearchConfig.getDefaultConfig 
    
    val articleIndexer = inject[ArticleIndexer]
    val uriGraph = inject[URIGraph]
    val searcher = new MainSearcher(userId, friendIds, filterOut, articleIndexer, uriGraph, config)
    val searchRes = searcher.search(term, maxHits, lastUUID)
    val res = toPersonalSearchResultPacket(searchRes)
    Ok(RPS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  private[controllers] def toPersonalSearchResultPacket(res: ArticleSearchResult) = {
    val hits = CX.withConnection { implicit conn =>
      res.hits map toPersonalSearchResult
    }
    println(hits mkString "\n")
    
    val filter = IdFilterCompressor.fromSetToBase64(res.filter)
    PersonalSearchResultPacket(res.uuid, res.query, hits, res.mayHaveMoreHits, filter)
  }
  private[controllers] def toPersonalSearchResult(res: ArticleHit)(implicit conn: Connection): PersonalSearchResult = {
    val uri = NormalizedURI.get(res.uriId)
    val users = res.users.toSeq.map{ userId =>
      val user = User.get(userId) 
      val info = SocialUserInfo.getByUser(user.id.get).head
      UserWithSocial(user, info)
    }
    PersonalSearchResult(uri, res.bookmarkCount, res.isMyBookmark, false, users, res.score)
  }
  
}