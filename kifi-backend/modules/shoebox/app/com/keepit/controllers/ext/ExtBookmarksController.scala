package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.KestrelCombinator
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier, HealthcheckPlugin}
import com.keepit.common.time._
import com.keepit.commanders.BookmarkInterner
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.http.Status.ACCEPTED
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import scala.Some
import play.api.libs.json.JsNumber
import com.keepit.model.KeepToCollection

private case class SendableBookmark(
  id: ExternalId[Bookmark],
  title: Option[String],
  url: String,
  isPrivate: Boolean,
  state: State[Bookmark]
)

private object SendableBookmark {
  private implicit val externalIdFormat = ExternalId.format[Bookmark]
  private implicit val stateFormat = State.format[Bookmark]
  implicit val writesSendableBookmark = Json.writes[SendableBookmark]

  def fromBookmark(b: Bookmark): SendableBookmark =
    SendableBookmark(b.externalId, b.title, b.url, b.isPrivate, b.state)
}

class ExtBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkInterner: BookmarkInterner,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  searchClient: SearchServiceClient,
  healthcheck: HealthcheckPlugin,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  airbrake: AirbrakeNotifier,
  bookmarksCommander: BookmarksCommander)
    extends BrowserExtensionController(actionAuthenticator) {

  def removeTag(id: ExternalId[Collection]) = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
    bookmarksCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  def createTag() = AuthenticatedJsonToJsonAction { request =>
    val name = (request.body \ "name").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    Ok(Json.toJson(SendableTag from tag))
  }

  def addTag(id: ExternalId[Collection]) = AuthenticatedJsonToJsonAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
    db.readWrite { implicit s =>
      collectionRepo.getOpt(id) map { tag =>
        bookmarksCommander.tagUrl(tag, request.body, request.user, request.experiments, BookmarkSource.hover, request.kifiInstallationId)
        Ok(Json.toJson(SendableTag from tag))
      } getOrElse {
        BadRequest(Json.obj("error" -> "noSuchTag"))
      }
    }
  }

  def addToUrl() = AuthenticatedJsonToJsonAction { request =>
    val name = (request.body \ "name").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    bookmarksCommander.tagUrl(tag, request.body, request.user, request.experiments, BookmarkSource.hover, request.kifiInstallationId)
    Ok(Json.toJson(SendableTag from tag))
  }

  def clearTags() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit s =>
      for {
        u <- uriRepo.getByUri(url).toSeq
        b <- bookmarkRepo.getByUri(u.id.get).toSeq
        ktc <- keepToCollectionRepo.getByBookmark(b.id.get)
      } {
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
      }
    }
    Ok(Json.obj())
  }

  def tags() = AuthenticatedJsonAction { request =>
    val tags = db.readOnly { implicit s =>
      collectionRepo.getByUser(request.userId)
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def tagsByUrl() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val tags = db.readOnly { implicit s =>
      for {
        uri <- uriRepo.getByUri(url).toSeq
        bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).toSeq
        collectionId <- keepToCollectionRepo.getCollectionsForBookmark(bookmark.id.get)
      } yield {
        collectionRepo.get(collectionId)
      }
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def remove() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readOnly { implicit s =>
      uriRepo.getByUri(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
      val deactivatedKeepInfo = bookmarksCommander.unkeepMultiple(Seq(KeepInfo.fromBookmark(bookmark)), request.userId).head
      Ok(Json.obj(
        "removedKeep" -> deactivatedKeepInfo
      ))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def updatePrivacy() = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    val (url, priv) = ((json \ "url").as[String], (json \ "private").as[Boolean])
    val bookmarkOpt = db.readOnly { implicit s =>
      uriRepo.getByUri(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    }
    val maybeOk = for {
      bookmark <- bookmarkOpt
      updatedBookmark <- {
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, BookmarkSource.hover).build
        bookmarksCommander.updateKeep(bookmark, Some(priv), None)
      }
    } yield Ok(Json.toJson(SendableBookmark fromBookmark updatedBookmark))

    maybeOk getOrElse NotFound
  }

  private val MaxBookmarkJsonSize = 2 * 1024 * 1024 // = 2MB, about 14.5K bookmarks

  def addBookmarks() = AuthenticatedJsonAction(parse.tolerantJson(maxLength = MaxBookmarkJsonSize)) { request =>
    val tStart = currentDateTime
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body

    val bookmarkSource = (json \ "source").asOpt[String].map(BookmarkSource(_)) getOrElse BookmarkSource.unknown
    if (!BookmarkSource.valid.contains(bookmarkSource)) {
      airbrake.notify(AirbrakeError.incoming(request, new IllegalStateException(s"Invalid bookmark source: $bookmarkSource")))
    }
    bookmarkSource match {
      case BookmarkSource("PLUGIN_START") => Forbidden
      case _ =>
        SafeFuture {
          log.debug("adding bookmarks of user %s".format(userId))
          val experiments = request.experiments
          val user = db.readOnly { implicit s => userRepo.get(userId) }
          implicit val context = heimdalContextBuilder.withRequestInfo(request).build
          val bookmarks = bookmarkInterner.internBookmarks(json \ "bookmarks", user, experiments, bookmarkSource, installationId)
          //the bookmarks list may be very large!
          bookmarks.grouped(50) foreach { chunk =>
            searchClient.updateBrowsingHistory(userId, chunk.map(_.uriId): _*)
          }
          searchClient.updateURIGraph()
        }
        Status(ACCEPTED)(JsNumber(0))
    }
  }

  def getNumMutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    val n: Int = db.readOnly { implicit s =>
      bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get)
    }
    Ok(Json.obj("n" -> n))
  }

}
