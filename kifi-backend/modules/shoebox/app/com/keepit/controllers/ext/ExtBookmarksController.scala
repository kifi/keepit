package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier, HealthcheckPlugin }
import com.keepit.common.time._
import com.keepit.commanders.KeepInterner
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import scala.Some
import play.api.libs.json.JsNumber
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import com.keepit.normalizer.NormalizedURIInterner

private case class SendableBookmark(
  id: ExternalId[Keep],
  title: Option[String],
  url: String,
  isPrivate: Boolean,
  state: State[Keep])

private object SendableBookmark {
  private implicit val externalIdFormat = ExternalId.format[Keep]
  private implicit val stateFormat = State.format[Keep]
  implicit val writesSendableBookmark = Json.writes[SendableBookmark]

  def fromBookmark(b: Keep): SendableBookmark =
    SendableBookmark(b.externalId, b.title, b.url, b.isPrivate, b.state)
}

class ExtBookmarksController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  bookmarkInterner: KeepInterner,
  keepRepo: KeepRepo,
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
  libraryCommander: LibraryCommander,
  clock: Clock,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def removeTag(id: ExternalId[Collection]) = UserAction(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    bookmarksCommander.removeTag(id, url, request.userId)
    Ok(Json.obj())
  }

  // Move to an endpoint that accepts a libraryId: PublicId[Library] and tag: String instead!
  def addTag(id: ExternalId[Collection]) = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val url = (request.body \ "url").as[String]
    val libraryId = {
      db.readWrite { implicit session =>
        val libIdOpt = for {
          uri <- normalizedURIInterner.getByUri(url)
          keep <- keepRepo.getByUriAndUser(uri.id.get, request.userId)
          libraryId <- keep.libraryId
        } yield libraryId
        libIdOpt.getOrElse {
          libraryCommander.getMainAndSecretLibrariesForUser(request.userId)._2.id.get // default to secret library if we can't find the keep
        }
      }
    }
    db.readWrite { implicit s =>
      collectionRepo.getOpt(id) map { tag =>
        bookmarksCommander.tagUrl(tag, Seq(RawBookmarkRepresentation(url = url, isPrivate = None, keptAt = Some(clock.now))), request.userId, libraryId, KeepSource.keeper, request.kifiInstallationId)
        Ok(Json.toJson(SendableTag from tag.summary))
      } getOrElse {
        BadRequest(Json.obj("error" -> "noSuchTag"))
      }
    }
  }

  def createAndApplyTag() = UserAction(parse.tolerantJson) { request =>
    val name = (request.body \ "name").as[String]
    val url = (request.body \ "url").as[String]
    val libraryId = {
      db.readWrite { implicit session =>
        val libIdOpt = for {
          uri <- normalizedURIInterner.getByUri(url)
          keep <- keepRepo.getByUriAndUser(uri.id.get, request.userId)
          libraryId <- keep.libraryId
        } yield libraryId
        libIdOpt.getOrElse {
          libraryCommander.getMainAndSecretLibrariesForUser(request.userId)._2.id.get // default to secret library if we can't find the keep
        }
      }
    }
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val tag = bookmarksCommander.getOrCreateTag(request.userId, name)
    bookmarksCommander.tagUrl(tag, Seq(RawBookmarkRepresentation(url = url, isPrivate = None, keptAt = Some(clock.now))), request.userId, libraryId, KeepSource.keeper, request.kifiInstallationId)
    Ok(Json.toJson(SendableTag from tag.summary))
  }

  def clearTags() = UserAction(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    bookmarksCommander.clearTags(url, request.userId)
    Ok(Json.obj())
  }

  def tags() = UserAction { request =>
    val tags = db.readOnlyMaster { implicit s =>
      collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(request.userId)
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def tagsByUrl() = UserAction(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    val tags = bookmarksCommander.tagsByUrl(url, request.userId)
    Ok(Json.toJson(tags.map(t => SendableTag.from(t.summary))))
  }

  // deprecated: use unkeep
  def remove() = UserAction(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url).flatMap { uri =>
        keepRepo.getByUriAndUser(uri.id.get, request.userId)
      }
    } map { bookmark =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      bookmarksCommander.unkeepMultiple(Seq(RawBookmarkRepresentation(url = bookmark.url, isPrivate = None)), request.userId).headOption match {
        case Some(deactivatedKeepInfo) =>
          Ok(Json.obj("removedKeep" -> deactivatedKeepInfo))
        case None =>
          Ok(Json.obj("removedKeep" -> JsNull))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  // migrate to endpoint that uses libraryId: PublicId[Library]
  def keep() = UserAction(parse.tolerantJson) { request =>
    val info = request.body.as[JsObject]
    val source = KeepSource.keeper
    val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
    if ((info \ "guided").asOpt[Boolean].getOrElse(false)) {
      hcb += ("guided", true)
    }
    implicit val context = hcb.build
    val rawBookmark = info.as[RawBookmarkRepresentation]
    val libraryId = {
      db.readWrite { implicit session =>
        val (main, secret) = libraryCommander.getMainAndSecretLibrariesForUser(request.userId)
        if (rawBookmark.isPrivate.exists(l => l)) {
          secret.id.get
        } else {
          main.id.get
        }
      }
    }

    val (keep, _) = bookmarksCommander.keepOne(rawBookmark, request.userId, libraryId, request.kifiInstallationId, source)
    Ok(Json.toJson(KeepInfo.fromKeep(keep)))
  }

  def unkeep(id: ExternalId[Keep]) = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    bookmarksCommander.unkeep(id, request.userId) map { ki =>
      Ok(Json.toJson(ki))
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def updateKeepInfo() = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val url = (json \ "url").as[String]
    val privateKeep = (json \ "private").asOpt[Boolean]
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

  // deprecated, remove after users are switched to libraries - Andrew (Oct 14 2014)
  def addBookmarks() = UserAction(parse.tolerantJson(maxLength = MaxBookmarkJsonSize)) { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body
    val libPubId = (request.body \ "libraryId").asOpt[String].map(id => PublicId[Library](id))

    val bookmarkSource = (json \ "source").asOpt[String].map(KeepSource.get) getOrElse KeepSource.unknown
    if (!KeepSource.valid.contains(bookmarkSource)) {
      val message = s"Invalid bookmark source: $bookmarkSource from user ${request.user} running extension ${request.kifiInstallationId}"
      airbrake.notify(AirbrakeError.incoming(request, new IllegalStateException(message), message, Some(request.user)))
    }
    SafeFuture {
      log.debug(s"adding bookmarks import of user $userId")

      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, bookmarkSource).build
      if (libPubId.isEmpty) {
        bookmarkInterner.persistRawKeeps(rawKeepFactory.toRawKeep(userId, bookmarkSource, json, installationId = installationId, libraryId = None))
      } else {
        val idTry = Library.decodePublicId(libPubId.get)
        idTry match {
          case Failure(ex) =>
            airbrake.notify(s"Invalid Library Public Id ${libPubId.get} when importing user $userId bookmarks")
          case Success(id) =>
            bookmarkInterner.persistRawKeeps(rawKeepFactory.toRawKeep(userId, bookmarkSource, json, installationId = installationId, libraryId = Some(id)))
        }
      }
    }
    Status(ACCEPTED)(JsNumber(0))
  }

}
