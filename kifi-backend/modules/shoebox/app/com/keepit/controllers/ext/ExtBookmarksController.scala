package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier, HealthcheckPlugin}
import com.keepit.common.time._
import com.keepit.commanders.KeepInterner
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import scala.Some
import play.api.libs.json.JsNumber
import scala.util.{Failure, Success}
import com.keepit.normalizer.NormalizedURIInterner

private case class SendableBookmark(
  id: ExternalId[Keep],
  title: Option[String],
  url: String,
  isPrivate: Boolean,
  state: State[Keep]
)

private object SendableBookmark {
  private implicit val externalIdFormat = ExternalId.format[Keep]
  private implicit val stateFormat = State.format[Keep]
  implicit val writesSendableBookmark = Json.writes[SendableBookmark]

  def fromBookmark(b: Keep): SendableBookmark =
    SendableBookmark(b.externalId, b.title, b.url, b.isPrivate, b.state)
}

class ExtBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkInterner: KeepInterner,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  collectionRepo: CollectionRepo,
  healthcheck: HealthcheckPlugin,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  bookmarksCommander: KeepsCommander,
  userValueRepo: UserValueRepo,
  airbrake: AirbrakeNotifier,
  kifiInstallationRepo: KifiInstallationRepo,
  rawBookmarkFactory: RawBookmarkFactory,
  rawKeepFactory: RawKeepFactory,
  searchClient: SearchServiceClient,
  normalizedURIInterner: NormalizedURIInterner,
  clock: Clock)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController{

  def removeTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    bookmarksCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  def addTag(id: ExternalId[Collection]) = JsonAction.authenticatedParseJson { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    db.readWrite { implicit s =>
      collectionRepo.getOpt(id) map { tag =>
        bookmarksCommander.tagUrl(tag, request.body, request.userId, KeepSource.keeper, request.kifiInstallationId)
        Ok(Json.toJson(SendableTag from tag.summary))
      } getOrElse {
        BadRequest(Json.obj("error" -> "noSuchTag"))
      }
    }
  }

  def createAndApplyTag() = JsonAction.authenticatedParseJson { request =>
    val name = (request.body \ "name").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    bookmarksCommander.tagUrl(tag, request.body, request.userId, KeepSource.keeper, request.kifiInstallationId)
    Ok(Json.toJson(SendableTag from tag.summary))
  }

  def clearTags() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    bookmarksCommander.clearTags(url, request.userId)
    Ok(Json.obj())
  }

  def tags() = JsonAction.authenticated { request =>
    val tags = db.readOnlyMaster { implicit s =>
      collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(request.userId)
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def tagsByUrl() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    val tags = bookmarksCommander.tagsByUrl(url, request.userId)
    Ok(Json.toJson(tags.map(t => SendableTag.from(t.summary))))
  }

  // deprecated: use unkeep
  def remove() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url).flatMap { uri =>
        keepRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      bookmarksCommander.unkeepMultiple(Seq(KeepInfo.fromBookmark(bookmark)), request.userId).headOption match {
        case Some(deactivatedKeepInfo) =>
          Ok(Json.obj("removedKeep" -> deactivatedKeepInfo))
        case None =>
          Ok(Json.obj("removedKeep" -> JsNull))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def keep() = JsonAction.authenticatedParseJson { request =>
    val info = request.body.as[JsObject]
    val source = KeepSource.keeper
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    Ok(Json.toJson(bookmarksCommander.keepOne(info, request.userId, request.kifiInstallationId, source)))
  }

  def unkeep(id: ExternalId[Keep]) = JsonAction.authenticated { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    bookmarksCommander.unkeep(id, request.userId) map { ki =>
      Ok(Json.toJson(ki))
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def updateKeepInfo() = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val url = (json \ "url").as[String]
    val privateKeep =  (json \ "private").asOpt[Boolean]
    val title = (json \ "title").asOpt[String]

    val bookmarkOpt = db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url).flatMap { uri =>
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

  // addBookmarks will soon handle *only* browser bookmark imports
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
          bookmarkInterner.internRawBookmarks(rawBookmarkFactory.toRawBookmarks(json), request.userId, bookmarkSource, mutatePrivacy = true, installationId = request.kifiInstallationId)
          searchClient.updateURIGraph()
        }
        Status(ACCEPTED)(JsNumber(0))
    }
  }

}
