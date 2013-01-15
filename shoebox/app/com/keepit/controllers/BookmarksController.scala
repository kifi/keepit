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
import com.keepit.common.net._
import com.keepit.common.async._
import com.keepit.search.graph.URIGraphPlugin
import play.api.libs.json.{JsBoolean, JsNull}
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.EventFamilies

object BookmarksController extends FortyTwoController {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    CX.withConnection { implicit conn =>
      val bookmark = BookmarkCxRepo.get(id)
      val uri = NormalizedURICxRepo.get(bookmark.uriId)
      val user = UserWithSocial.toUserWithSocial(UserCxRepo.get(bookmark.userId))
      Ok(views.html.bookmark(bookmark, uri, user))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Bookmark], isPrivate: Boolean)(implicit conn: Connection): Id[User] = {
      val bookmark = BookmarkCxRepo.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      bookmark.withPrivate(isPrivate).save
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Bookmark], isActive: Boolean)(implicit conn: Connection): Id[User] = {
      val bookmark = BookmarkCxRepo.get(id)
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
    Redirect(request.request.referer)
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = AdminHtmlAction { request =>
    CX.withConnection { implicit conn =>
      val bookmark = BookmarkCxRepo.get(id)
      bookmark.delete()
      inject[URIGraphPlugin].update(bookmark.userId)
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = CX.withConnection { implicit conn =>
      BookmarkCxRepo.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, bookmarksAndUsers) = CX.withConnection { implicit conn =>
      val bookmarks = BookmarkCxRepo.page(page, PAGE_SIZE)
      val users = bookmarks map (_.userId) map UserCxRepo.get map UserWithSocial.toUserWithSocial
      val uris = bookmarks map (_.uriId) map NormalizedURICxRepo.get map (_.stats)
      val count = BookmarkCxRepo.count
      (count, (bookmarks, uris, users).zipped.toList.seq)
    }
    val pageCount: Int = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }

  def checkIfExists(uri: String) = AuthenticatedJsonAction { request =>
    val bookmark = CX.withConnection { implicit conn =>
      NormalizedURICxRepo.getByNormalizedUrl(uri).flatMap { uri =>
        BookmarkCxRepo.load(uri, request.userId).filter(_.isActive)
      }
    }

    Ok(JsObject(Seq("user_has_bookmark" -> JsBoolean(bookmark.isDefined))))
  }

  // TODO: Remove parameter and only check request body once all installations are 2.1.6 or later.
  def remove(uri: Option[String]) = AuthenticatedJsonAction { request =>
    val url = uri.getOrElse((request.body.asJson.get \ "url").as[String])
    val bookmark = CX.withConnection{ implicit conn =>
      NormalizedURICxRepo.getByNormalizedUrl(url).flatMap { uri =>
        BookmarkCxRepo.load(uri, request.userId).filter(_.isActive).map {b => b.withActive(false).save}
      }
    }
    inject[URIGraphPlugin].update(request.userId)
    bookmark match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  // TODO: Remove parameters and only check request body once all installations are 2.1.6 or later.
  def updatePrivacy(uri: Option[String], isPrivate: Option[Boolean]) = AuthenticatedJsonAction { request =>
    val (url, priv) = request.body.asJson match {
      case Some(o) => ((o \ "url").as[String], (o \ "private").as[Boolean])
      case _ => (uri.get, isPrivate.get)
    }
    CX.withConnection{ implicit conn =>
      NormalizedURICxRepo.getByNormalizedUrl(url).flatMap { uri =>
        BookmarkCxRepo.load(uri, request.userId).filter(_.isPrivate != priv).map {b => b.withPrivate(priv).save}
      }
    } match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def addBookmarks() = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    request.body.asJson match {
      case Some(json) =>  // TODO: remove bookmark_source check after everyone is at v2.1.6 or later.
        val bookmarkSource = (json \ "bookmark_source").asOpt[String].orElse((json \ "source").asOpt[String])
        bookmarkSource match {
          case Some("PLUGIN_START") => Forbidden
          case _ =>
            log.info("adding bookmarks of user %s".format(userId))
            val experiments = request.experimants
            val user = CX.withConnection { implicit conn => UserCxRepo.get(userId) }
            internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
            inject[URIGraphPlugin].update(userId)
            Ok(JsObject(Seq()))
        }
      case None =>
        val (user, experiments, installation) = CX.withConnection { implicit conn =>
          (UserCxRepo.get(userId),
           UserExperimentCxRepo.getByUser(userId) map (_.experimentType),
           installationId.map(_.id).getOrElse(""))
        }
        val msg = "Unsupported operation for user %s with old installation".format(userId)
        val metaData = JsObject(Seq("message" -> JsString(msg)))
        val event = Events.userEvent(EventFamilies.ACCOUNT, "deprecated_add_bookmarks", user, experiments, installation, metaData)
        dispatch ({
           event.persistToS3().persistToMongo()
        }, { e =>
          inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.API,
              errorMessage = Some("Can't persist event %s".format(event))))
        })
        BadRequest(msg)
    }
  }

  private def internBookmarks(value: JsValue, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, experiments, source, installationId)} flatten).toList
    case json: JsObject if(json.keys.contains("children")) => internBookmarks(json \ "children" , user, experiments, source)
    case json: JsObject => List(internBookmark(json, user, experiments, source)).flatten
    case e => throw new Exception("can't figure what to do with %s".format(e))
  }

  private def internBookmark(json: JsObject, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): Option[Bookmark] = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    val isPrivate = try { (json \ "isPrivate").as[Boolean] } catch { case e => true }

    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))
      val (uri, isNewURI) = CX.withConnection { implicit conn =>
        NormalizedURICxRepo.getByNormalizedUrl(url) match {
          case Some(uri) => (uri, false)
          case None => (createNewURI(title, url), true)
        }
      }
      if (isNewURI) inject[ScraperPlugin].asyncScrape(uri)
      CX.withConnection { implicit conn =>
        BookmarkCxRepo.load(uri, user.id.get) match {
          case Some(bookmark) if bookmark.isActive => Some(bookmark) // TODO: verify isPrivate?
          case Some(bookmark) => Some(bookmark.withActive(true).withPrivate(isPrivate).save)
          case None =>
            Events.userEvent(EventFamilies.SLIDER, "newKeep", user, experiments, installationId.map(_.id).getOrElse(""), JsObject(Seq("source" -> JsString(source.value))))
            val urlObj = URLCxRepo.get(url).getOrElse(URLFactory(url = url, normalizedUriId = uri.id.get).save)
            Some(BookmarkFactory(uri, user.id.get, title, urlObj, source, isPrivate, installationId).save)
        }
      }
    } else {
      None
    }
  }

  private def createNewURI(title: String, url: String)(implicit conn: Connection) = {
    NormalizedURIFactory(title = title, url = url).save
  }

}
