package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.KestrelCombinator
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
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
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  collectionRepo: CollectionRepo,
  healthcheck: HealthcheckPlugin,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  bookmarksCommander: BookmarksCommander,
  userValueRepo: UserValueRepo,
  airbrake: AirbrakeNotifier,
  kifiInstallationRepo: KifiInstallationRepo,
  rawBookmarkFactory: RawBookmarkFactory,
  rawKeepFactory: RawKeepFactory,
  searchClient: SearchServiceClient,
  clock: Clock)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController{

  def removeTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    bookmarksCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  def createTag() = JsonAction.authenticatedParseJson { request =>
    val name = (request.body \ "name").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    Ok(Json.toJson(SendableTag from tag))
  }

  def addTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    db.readWrite { implicit s =>
      collectionRepo.getOpt(id) map { tag =>
        bookmarksCommander.tagUrl(tag, request.body, request.userId, KeepSource.keeper, request.kifiInstallationId)
        Ok(Json.toJson(SendableTag from tag))
      } getOrElse {
        BadRequest(Json.obj("error" -> "noSuchTag"))
      }
    }
  }

  def addToUrl() = JsonAction.authenticatedParseJson { request =>
    val name = (request.body \ "name").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    bookmarksCommander.tagUrl(tag, request.body, request.userId, KeepSource.keeper, request.kifiInstallationId)
    Ok(Json.toJson(SendableTag from tag))
  }

  def clearTags() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    bookmarksCommander.clearTags(url, request.userId)
    Ok(Json.obj())
  }

  def tags() = JsonAction.authenticated { request =>
    val tags = db.readOnly { implicit s =>
      collectionRepo.getByUser(request.userId)
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def tagsByUrl() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    val tags = bookmarksCommander.tagsByUrl(url, request.userId)
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def remove() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    db.readOnly { implicit s =>
      uriRepo.getByUri(url).flatMap { uri =>
        keepRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      val deactivatedKeepInfo = bookmarksCommander.unkeepMultiple(Seq(KeepInfo.fromBookmark(bookmark)), request.userId).head
      Ok(Json.obj(
        "removedKeep" -> deactivatedKeepInfo
      ))
    } getOrElse {
      NotFound(Json.obj("error" -> "Keep not found"))
    }
  }

  def updateKeepInfo() = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val url = (json \ "url").as[String]
    val privateKeep =  (json \ "private").asOpt[Boolean]
    val title = (json \ "title").asOpt[String]

    val bookmarkOpt = db.readOnly { implicit s =>
      uriRepo.getByUri(url).flatMap { uri =>
        keepRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    }
    val maybeOk = for {
      bookmark <- bookmarkOpt
      updatedBookmark <- {
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
        bookmarksCommander.updateKeep(bookmark, privateKeep, title)
      }
    } yield Ok(Json.toJson(SendableBookmark fromBookmark updatedBookmark))

    maybeOk getOrElse NotFound
  }

  private val MaxBookmarkJsonSize = 2 * 1024 * 1024 // = 2MB, about 14.5K bookmarks

  def addBookmarks() = JsonAction.authenticated(parser = parse.tolerantJson(maxLength = MaxBookmarkJsonSize)) { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body

    val bookmarkSource = (json \ "source").asOpt[String].map(KeepSource.get) getOrElse KeepSource.unknown
    if (!KeepSource.valid.contains(bookmarkSource)) {
      val message = s"Invalid bookmark source: $bookmarkSource from user ${request.user} running extension ${request.kifiInstallationId}"
      airbrake.notify(AirbrakeError.incoming(request, new IllegalStateException(message), message, Some(request.user)))
    }
    bookmarkSource match {
      case KeepSource("plugin_start") => Forbidden
      case KeepSource.bookmarkImport =>
        SafeFuture {
          log.debug(s"adding bookmarks import of user $userId")

          implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, bookmarkSource).build
          bookmarkInterner.persistRawKeeps(rawKeepFactory.toRawKeep(userId, bookmarkSource, json, installationId = installationId))
        }
        Status(ACCEPTED)(JsNumber(0))
      case _ =>
        SafeFuture {
          log.debug(s"adding bookmarks of user $userId")

          implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, bookmarkSource).build
          bookmarkInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmark(json), request.userId, bookmarkSource, mutatePrivacy = true, installationId = request.kifiInstallationId)
          searchClient.updateURIGraph()
        }
        Status(ACCEPTED)(JsNumber(0))
    }
  }

  def getNumMutualKeeps(id: ExternalId[User]) = JsonAction.authenticated { request =>
    val n: Int = db.readOnly { implicit s =>
      keepRepo.getNumMutual(request.userId, userRepo.get(id).id.get)
    }
    Ok(Json.obj("n" -> n))
  }

}
