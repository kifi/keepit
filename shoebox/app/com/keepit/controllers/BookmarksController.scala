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
import com.keepit.serializer.{ URIPersonalSearchResultSerializer ⇒ BPSRS }
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.social._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.net.HttpClient

object BookmarksController extends Controller with Logging with SecureSocial {

  def edit(id: Id[Bookmark]) = Action { request ⇒
    CX.withConnection { implicit conn ⇒
      val bookmark = Bookmark.get(id)
      val user = UserWithSocial.toUserWithSocial(User.get(bookmark.userId))
      Ok(views.html.editBookmark(bookmark, user))
    }
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = SecuredAction(false) { request ⇒
    CX.withConnection { implicit conn ⇒
      val bookmark = Bookmark.get(id)
      bookmark.delete()
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView)
    }
  }

  def all = SecuredAction(true) { request ⇒
    val bookmarks = CX.withConnection { implicit conn ⇒
      Bookmark.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView = SecuredAction(false) { request ⇒
    val bookmarksAndUsers = CX.withConnection { implicit conn ⇒
      val bookmarks = Bookmark.all
      val users = bookmarks map (_.userId) map User.get map UserWithSocial.toUserWithSocial
      val uris = bookmarks map (_.uriId) map NormalizedURI.get map { u ⇒ u.stats() }
      (bookmarks, uris, users).zipped.toList.seq
    }
    Ok(views.html.bookmarks(bookmarksAndUsers))
  }

  def addBookmarks() = JsonAction { request ⇒
    val json = request.body
    log.debug(json)
    log.info("user_info = [%s]".format(json \ "user_info"))
    val bookmarkSource = (json \ "bookmark_source").asOpt[String]
    val keepitExternalId = parseKeepitExternalId(json \ "user_info")
    val user = CX.withConnection { implicit conn ⇒ User.get(keepitExternalId) }
    log.info("adding bookmarks of user %s".format(user))
    internBookmarks(json \ "bookmarks", user, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")))
    Ok(JsObject(("status" -> JsString("success")) ::
      ("userId" -> JsString(user.id.map(id ⇒ id.id.toString()).getOrElse(""))) :: Nil)) //todo: need to send external id
  }

  private def internBookmarks(value: JsValue, user: User, source: BookmarkSource): List[Bookmark] = value match {
    case JsArray(elements) ⇒ (elements map { e ⇒ internBookmarks(e, user, source) } flatten).toList
    case json: JsObject if (json.keys.contains("children")) ⇒ internBookmarks(json \ "children", user, source)
    case json: JsObject ⇒ List(internBookmark(json, user, source))
    case e ⇒ throw new Exception("can't figure what to do with %s".format(e))
  }

  private def parseSocialId(value: JsValue): SocialId = SocialId((value \ "facebook_id").as[String])

  private def parseKeepitExternalId(value: JsValue): ExternalId[User] = ExternalId[User](((value \ "keepit_external_id").as[String]))

  private def internBookmark(json: JsObject, user: User, source: BookmarkSource): Bookmark = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn ⇒
      val normalizedUri = NormalizedURI.getByNormalizedUrl(url) match {
        case Some(uri) ⇒ uri
        case None ⇒ createNewURI(title, url)
      }
      Bookmark.load(normalizedUri, user) match {
        case Some(bookmark) ⇒ bookmark
        case None ⇒ Bookmark(normalizedUri, user, title, url, source).save
      }
    }
  }

  private def createNewURI(title: String, url: String)(implicit conn: Connection) = {
    val uri = NormalizedURI(title, url).save
    inject[ScraperPlugin].asyncScrape(uri)
    uri
  }

}