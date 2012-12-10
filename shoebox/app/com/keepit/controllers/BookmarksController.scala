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
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.social._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.net.HttpClient
import com.keepit.search.graph.URIGraphPlugin
import play.api.libs.json.{JsBoolean, JsNull}
import com.keepit.common.controller.FortyTwoController

object BookmarksController extends FortyTwoController {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id)
      val uri = NormalizedURI.get(bookmark.uriId)
      val user = UserWithSocial.toUserWithSocial(User.get(bookmark.userId))
      Ok(views.html.bookmark(bookmark, uri, user))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Bookmark], isPrivate: Boolean)(implicit conn: Connection): Id[User] = {
      val bookmark = Bookmark.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      bookmark.withPrivate(isPrivate).save
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Bookmark], isActive: Boolean)(implicit conn: Connection): Id[User] = {
      val bookmark = Bookmark.get(id)
      log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
      bookmark.withActive(isActive).save
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    val uniqueUsers = CX.withConnection { implicit conn =>
      val modifiedUserIds = request.body.asFormUrlEncoded.get map { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => setIsPrivate(Id[Bookmark](id.toInt), toBoolean(values.last))
          case Array("active", id) => setIsActive(Id[Bookmark](id.toInt), toBoolean(values.last))
        }
      }
      Set(modifiedUserIds.toSeq: _*)
    }
    uniqueUsers foreach { userId =>
      log.info("updating user %s".format(userId))
      inject[URIGraphPlugin].update(userId)
    }
    Redirect(request.request.headers("referer"))
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = AdminHtmlAction { request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id)
      bookmark.delete()
      inject[URIGraphPlugin].update(bookmark.userId)
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = CX.withConnection { implicit conn =>
      Bookmark.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksFirstPageView = Action { // TODO: make admin only
    Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, bookmarksAndUsers) = CX.withConnection { implicit conn =>
      val bookmarks = Bookmark.page(page, PAGE_SIZE)
      val users = bookmarks map (_.userId) map User.get map UserWithSocial.toUserWithSocial
      val uris = bookmarks map (_.uriId) map NormalizedURI.get map (_.stats)
      val count = Bookmark.count
      (count, (bookmarks, uris, users).zipped.toList.seq)
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }

  def checkIfExists(uri: String) = AuthenticatedJsonAction { request =>
    val bookmark = CX.withConnection { implicit conn =>
      NormalizedURI.getByNormalizedUrl(uri).flatMap { uri =>
        Bookmark.load(uri, request.userId).filter(_.isActive)
      }
    }

    Ok(JsObject(Seq("user_has_bookmark" -> JsBoolean(bookmark.isDefined))))
  }

  def remove(url: String) = AuthenticatedJsonAction { request =>
    val bookmark = CX.withConnection{ implicit conn =>
      NormalizedURI.getByNormalizedUrl(url).flatMap { uri =>
        Bookmark.load(uri, request.userId).filter(_.isActive).map {b => b.withActive(false).save}
      }
    }
    inject[URIGraphPlugin].update(request.userId)
    bookmark match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def updatePrivacy(url: String, isPrivate: Boolean) = AuthenticatedJsonAction { request =>
    CX.withConnection{ implicit conn =>
      NormalizedURI.getByNormalizedUrl(url).flatMap { uri =>
        Bookmark.load(uri, request.userId).filter(_.isPrivate != isPrivate).map {b => b.withPrivate(isPrivate).save}
      }
    } match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def addBookmarks() = JsonAction { request =>
    val json = request.body
    log.debug(json)
    log.info("user_info = [%s]".format(json \ "user_info"))
    val bookmarkSource = (json \ "bookmark_source").asOpt[String]
    val userId = ExternalId[User]((json \ "user_info" \ "keepit_external_id").as[String])
    val user = CX.withConnection { implicit conn => User.get(userId) }
    log.info("adding bookmarks of user %s".format(user))
    internBookmarks(json \ "bookmarks", user, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")))
    inject[URIGraphPlugin].update(user.id.get)
    Ok(JsObject(("status" -> JsString("success")) ::
        ("userId" -> JsString(user.id.map(id => id.id.toString()).getOrElse(""))) :: Nil))//todo: need to send external id
  }

  private def internBookmarks(value: JsValue, user: User, source: BookmarkSource): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, source)} flatten).toList
    case json: JsObject if(json.keys.contains("children")) => internBookmarks(json \ "children" , user, source)
    case json: JsObject => List(internBookmark(json, user, source)).flatten
    case e => throw new Exception("can't figure what to do with %s".format(e))
  }

  private def internBookmark(json: JsObject, user: User, source: BookmarkSource): Option[Bookmark] = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    val isPrivate = try { (json \ "isPrivate").as[Boolean] } catch { case e => false }
    println("===== PRIVATE: " + isPrivate)

    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))
      CX.withConnection { implicit conn =>
        val uri = NormalizedURI.getByNormalizedUrl(url) match {
          case Some(uri) => uri
          case None => createNewURI(title, url)
        }
        Bookmark.load(uri, user) match {
          case Some(bookmark) if bookmark.isActive => Some(bookmark) // TODO: verify isPrivate?
          case Some(bookmark) => Some(bookmark.withActive(true).withPrivate(isPrivate).save)
          case None => Some(Bookmark(uri, user, title, url, source, isPrivate).save)
        }
      }
    } else {
      None
    }
  }

  private def createNewURI(title: String, url: String)(implicit conn: Connection) = {
    val uri = NormalizedURI(title = title, url = url).save
    inject[ScraperPlugin].asyncScrape(uri)
    uri
  }

}
