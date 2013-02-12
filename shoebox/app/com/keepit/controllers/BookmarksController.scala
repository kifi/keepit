package com.keepit.controllers

import com.keepit.classify.DomainClassifier
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.async._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.net._
import com.keepit.common.social._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.serializer.BookmarkSerializer

import akka.dispatch.Await
import akka.util.duration._
import play.api.Play.current
import play.api.libs.json._


object BookmarksController extends FortyTwoController {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    inject[DBConnection].readOnly { implicit session =>
      val bookmark = inject[BookmarkRepo].get(id)
      val uri = inject[NormalizedURIRepo].get(bookmark.uriId)
      val user = inject[UserWithSocialRepo].toUserWithSocial(inject[UserRepo].get(bookmark.userId))
      val scrapeInfo = inject[ScrapeInfoRepo].getByUri(bookmark.uriId)
      Ok(views.html.bookmark(user, bookmark, uri, scrapeInfo))
    }
  }

  def rescrape = AuthenticatedJsonAction { request =>
    val id = Id[Bookmark]((request.body.asJson.get \ "id").as[Int])
    inject[DBConnection].readOnly { implicit session =>
      val bookmark = inject[BookmarkRepo].get(id)
      val uri = inject[NormalizedURIRepo].get(bookmark.uriId)
      Await.result(inject[ScraperPlugin].asyncScrape(uri), 1 minutes)
      Ok(JsObject(Seq("status" -> JsString("ok"))))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Bookmark], isPrivate: Boolean)(implicit session: RWSession): Id[User] = {
      val repo = inject[BookmarkRepo]
      val bookmark = repo.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      repo.save(bookmark.withPrivate(isPrivate))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Bookmark], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val repo = inject[BookmarkRepo]
      val bookmark = repo.get(id)
      log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
      repo.save(bookmark.withActive(isActive))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    val uniqueUsers = inject[DBConnection].readWrite { implicit s =>
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
    inject[DBConnection].readWrite { implicit s =>
      val repo = inject[BookmarkRepo]
      val bookmark = repo.get(id)
      repo.delete(id)
      inject[URIGraphPlugin].update(bookmark.userId)
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = inject[DBConnection].readOnly(implicit session => inject[BookmarkRepo].all)
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, bookmarksAndUsers) = inject[DBConnection].readOnly { implicit s =>
      val userRepo = inject[UserRepo]
      val bookmarkRepo = inject[BookmarkRepo]
      val normalizedURIRepo = inject[NormalizedURIRepo]
      val scrapeInfoRepo = inject[ScrapeInfoRepo]
      val bookmarks = bookmarkRepo.page(page, PAGE_SIZE)
      val repo = inject[UserWithSocialRepo]
      val users = bookmarks map (_.userId) map userRepo.get map repo.toUserWithSocial
      val uris = bookmarks map (_.uriId) map normalizedURIRepo.get map (bookmarkRepo.uriStats)
      val scrapes = bookmarks map (_.uriId) map scrapeInfoRepo.getByUri
      val count = bookmarkRepo.count(s)
      (count, (users, (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq)
    }
    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(views.html.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }

  def checkIfExists(uri: String) = AuthenticatedJsonAction { request =>
    val (bookmark, sensitive) = inject[DBConnection].readOnly { implicit s =>
      val normalizedUri = inject[NormalizedURIRepo].getByNormalizedUrl(uri)
      val bookmark = normalizedUri.flatMap { uri =>
        inject[BookmarkRepo].getByUriAndUser(uri.id.get, request.userId).filter(_.isActive)
      }
      val sensitive = normalizedUri.flatMap(_.domain).map(inject[DomainClassifier].isSensitive).flatMap {
        case Left(_) => None
        case Right(opt) => opt
      }
      (bookmark, sensitive)
    }

    Ok(JsObject(Seq(
      "user_has_bookmark" -> JsBoolean(bookmark.isDefined),
      "sensitive" -> sensitive.map(JsBoolean(_)).getOrElse(JsNull)
    )))
  }

  // TODO: Remove parameter and only check request body once all installations are 2.1.6 or later.
  def remove(uri: Option[String]) = AuthenticatedJsonAction { request =>
    val url = uri.getOrElse((request.body.asJson.get \ "url").as[String])
    val repo = inject[BookmarkRepo]
    val bookmark = inject[DBConnection].readWrite { implicit s =>
      inject[NormalizedURIRepo].getByNormalizedUrl(url).flatMap { uri =>
        repo.getByUriAndUser(uri.id.get, request.userId).map { b =>
          repo.save(b.withActive(false))
        }
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
    val repo = inject[BookmarkRepo]
    inject[DBConnection].readWrite { implicit s =>
      inject[NormalizedURIRepo].getByNormalizedUrl(url).flatMap { uri =>
        repo.getByUriAndUser(uri.id.get, request.userId).filter(_.isPrivate != priv).map {b =>
          repo.save(b.withPrivate(priv))
        }
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
            val user = inject[DBConnection].readOnly { implicit s => inject[UserRepo].get(userId) }
            internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
            inject[URIGraphPlugin].update(userId)
            Ok(JsObject(Seq()))
        }
      case None =>
        val (user, experiments, installation) = inject[DBConnection].readOnly{ implicit session =>
          (inject[UserRepo].get(userId),
           inject[UserExperimentRepo].getByUser(userId) map (_.experimentType),
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
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)

    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))
      val (uri, isNewURI) = inject[DBConnection].readWrite { implicit s =>
        inject[NormalizedURIRepo].getByNormalizedUrl(url) match {
          case Some(uri) => (uri, false)
          case None => (createNewURI(title, url), true)
        }
      }
      if (isNewURI) inject[ScraperPlugin].asyncScrape(uri)
      val repo = inject[BookmarkRepo]
      val urlRepo = inject[URLRepo]
      inject[DBConnection].readWrite { implicit s =>
        repo.getByUriAndUser(uri.id.get, user.id.get) match {
          case Some(bookmark) if bookmark.isActive => Some(bookmark) // TODO: verify isPrivate?
          case Some(bookmark) => Some(repo.save(bookmark.withActive(true).withPrivate(isPrivate)))
          case None =>
            Events.userEvent(EventFamilies.SLIDER, "newKeep", user, experiments, installationId.map(_.id).getOrElse(""), JsObject(Seq("source" -> JsString(source.value))))
            val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
            Some(repo.save(BookmarkFactory(uri, user.id.get, title, urlObj, source, isPrivate, installationId)))
        }
      }
    } else {
      None
    }
  }

  private def createNewURI(title: String, url: String)(implicit session: RWSession) =
    inject[NormalizedURIRepo].save(NormalizedURIFactory(title = title, url = url))
}
