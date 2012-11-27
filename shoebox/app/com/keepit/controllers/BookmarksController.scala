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
    val uniqueUsers = CX.withConnection { implicit conn =>
      val modifiedUserIds = request.body.asFormUrlEncoded.get map { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => privateBookmark(Id[Bookmark](id.toInt), toBoolean(values.last))
          case Array("active", id) => activeBookmark(Id[Bookmark](id.toInt), toBoolean(values.last))
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

  private def toBoolean(str: String) = {println(str.trim); str.trim.toInt == 1}

  private def privateBookmark(id: Id[Bookmark], isPrivate: Boolean)(implicit conn: Connection): Id[User] = {
    val bookmark = Bookmark.get(id)
    log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
    bookmark.withPrivate(isPrivate).save
    log.info("updated bookmark %s".format(bookmark))
    bookmark.userId
  }

  private def activeBookmark(id: Id[Bookmark], isActive: Boolean)(implicit conn: Connection): Id[User] = {
    val bookmark = Bookmark.get(id)
    log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
    bookmark.withActive(isActive).save
    log.info("updated bookmark %s".format(bookmark))
    bookmark.userId
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

  def checkIfExists(externalId: ExternalId[User], uri: String) = AuthenticatedJsonAction { request =>
    val userHasBookmark = CX.withConnection { implicit conn =>
      NormalizedURI.getByNormalizedUrl(uri).map { uri =>
        Bookmark.load(uri, request.userId).isDefined // .state == ACTIVE ?
      }.getOrElse(false)
    }

    Ok(JsObject(("user_has_bookmark" -> JsBoolean(userHasBookmark)) :: Nil))
  }

  def removeBookmark(externalId: ExternalId[User], externalBookmarkId: ExternalId[Bookmark]) = JsonAction { request =>
    val (user,bookmark) = CX.withConnection{ implicit conn =>
      val user = User.getOpt(externalId).getOrElse(throw new Exception("user externalId %s not found".format(externalId)))
      val bookmark = Bookmark.getOpt(externalBookmarkId).getOrElse(
                throw new Exception("bookmark externalId %s not found".format(externalId))).withActive(false).save
      (user, bookmark)
    }
    inject[URIGraphPlugin].update(user.id.get)

    Ok(JsObject(("status" -> JsString("success")) :: Nil))
  }

  def updatePrivacy(externalId: ExternalId[Bookmark], isPrivate: Boolean) = JsonAction { request =>
    val bookmark = CX.withConnection{ implicit conn =>
      Bookmark.getOpt(externalId).getOrElse(
                throw new Exception("externalId %s not found".format(externalId))).withPrivate(isPrivate).save
    }
    Ok(JsObject(("status" -> JsString("success")) :: ("isPrivate" -> JsBoolean(isPrivate))  :: Nil))
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
    val isPrivate = try { (json \ "isPrivate").as[Boolean] } catch { case e => false }

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
            case None => Some(Bookmark(normalizedUri, user, title, url, source, isPrivate).save)
          }
        }
      case true =>
        None
    }
  }

  private def createNewURI(title: String, url: String)(implicit conn: Connection) = {
    val uri = NormalizedURI(title = title, url = url).save
    inject[ScraperPlugin].asyncScrape(uri)
    uri
  }

}
