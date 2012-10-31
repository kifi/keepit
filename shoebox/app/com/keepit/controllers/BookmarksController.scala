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
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.social._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.net.HttpClient
import com.keepit.search.graph.URIGraphPlugin
import play.api.libs.json.JsBoolean

object BookmarksController extends Controller with Logging with SecureSocial {

  def edit(id: Id[Bookmark]) = Action{ request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id) 
      val user = UserWithSocial.toUserWithSocial(User.get(bookmark.userId))
      Ok(views.html.editBookmark(bookmark, user))
    }
  }
  
  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = SecuredAction(false) { request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id)
      bookmark.delete()
      inject[URIGraphPlugin].update(bookmark.userId)
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
    }
  }  
  
  def all = SecuredAction(true) { request =>
    val bookmarks = CX.withConnection { implicit conn =>
      Bookmark.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }
  
  def bookmarksFirstPageView = Action {
    Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
  }
    
  def bookmarksView(page: Int = 0) = SecuredAction(false) { request =>
    val pageSize = 40
    val (count, bookmarksAndUsers) = CX.withConnection { implicit conn =>
      val bookmarks = Bookmark.page(page, pageSize)
      val users = bookmarks map (_.userId) map User.get map UserWithSocial.toUserWithSocial
      val uris = bookmarks map (_.uriId) map NormalizedURI.get map {u => u.stats()}
      val count = Bookmark.count
      (count, (bookmarks, uris, users).zipped.toList.seq)
    }
    val pageCount: Int = (count / pageSize + 1).toInt
    Ok(views.html.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }
  
  def checkIfExists(externalId: ExternalId[User], uri: String) = SecuredAction(true) { request =>
    val (normalizedURIOpt, bookmarksOpt) = CX.withConnection { implicit conn =>
      val normalizedURI = NormalizedURI.getByNormalizedUrl(uri)
      val user = User.getOpt(externalId)
      val bookmarks = user match { case Some(u) => Some(Bookmark.ofUser(u)) case None => None }
      
      (normalizedURI, bookmarks)
    }
    
    val userHasBookmark = 
      (for {
        normalizedURI <- normalizedURIOpt
        bookmarks <- bookmarksOpt
      } yield bookmarks.map(b => b.uriId).contains(normalizedURI.id.get)
      ) getOrElse(false)
    
    Ok(JsObject(("user_has_bookmark" -> JsBoolean(userHasBookmark)) :: Nil))
  }
  
  def addBookmarks() = JsonAction { request =>
    val json = request.body
    log.debug(json)
    log.info("user_info = [%s]".format(json \ "user_info"))
    val bookmarkSource = (json \ "bookmark_source").asOpt[String]
    val keepitExternalId = parseKeepitExternalId(json \ "user_info")
    val user = CX.withConnection { implicit conn => User.get(keepitExternalId) }
    log.info("adding bookmarks of user %s".format(user))
    internBookmarks(json \ "bookmarks", user, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN"))) 
    inject[URIGraphPlugin].update(user.id.get)
    Ok(JsObject(("status" -> JsString("success")) :: 
        ("userId" -> JsString(user.id.map(id => id.id.toString()).getOrElse(""))) :: Nil))//todo: need to send external id
  }
  
  private def internBookmarks(value: JsValue, user: User, source: BookmarkSource): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, source)} flatten).toList  
    case json: JsObject if(json.keys.contains("children")) => internBookmarks( json \ "children" , user, source)  
    case json: JsObject => List(internBookmark(json, user, source)).flatten
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseSocialId(value: JsValue): SocialId = SocialId((value \ "facebook_id").as[String])

  private def parseKeepitExternalId(value: JsValue): ExternalId[User] = ExternalId[User](((value \ "keepit_external_id").as[String]))
  
  private def internBookmark(json: JsObject, user: User, source: BookmarkSource): Option[Bookmark] = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]

    url.toLowerCase.startsWith("javascript:") match {
      case false =>
        log.debug("interning bookmark %s with title [%s]".format(json, title))
        CX.withConnection { implicit conn =>
          val normalizedUri = NormalizedURI.getByNormalizedUrl(url) match {
            case Some(uri) => uri
            case None => createNewURI(title, url)
          }
          Bookmark.load(normalizedUri, user) match {
            case Some(bookmark) => Some(bookmark)
            case None => Some(Bookmark(normalizedUri, user, title, url, source).save)
          }
        }
      case true =>
        None
    }
  }
  
  private def createNewURI(title: String, url: String)(implicit conn: Connection) = {
    val uri = NormalizedURI(title, url).save
    inject[ScraperPlugin].asyncScrape(uri)
    uri
  }
  
}