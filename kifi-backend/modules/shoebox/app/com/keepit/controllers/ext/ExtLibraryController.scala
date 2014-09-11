package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepsCommander, RawBookmarkRepresentation, LibraryCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.{ LibraryMembershipRepo, KeepSource, Library, LibraryAccess }
import play.api.libs.json._

import scala.util.{ Success, Failure }

class ExtLibraryController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  libraryCommander: LibraryCommander,
  keepsCommander: KeepsCommander,
  basicUserRepo: BasicUserRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getLibraries() = JsonAction.authenticated { request =>
    val (libraries, _) = libraryCommander.getLibrariesByUser(request.userId)
    val libsCanKeepTo = libraries.filter(_._1 != LibraryAccess.READ_ONLY)
    val jsons = libsCanKeepTo.map { a =>
      val lib = a._2
      val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
      Json.obj(
        "id" -> Library.publicId(lib.id.get).id,
        "name" -> lib.name,
        "path" -> Library.formatLibraryUrl(owner.username, owner.externalId, lib.slug),
        "visibility" -> Json.toJson(lib.visibility))
    }
    Ok(Json.obj("libraries" -> Json.toJson(jsons)))
  }

  def addKeep(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))

      case Success(libraryId) =>
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getOpt(request.userId, libraryId)
        } match {
          case Some(mem) if mem.access != LibraryAccess.READ_ONLY =>
            val info = request.body.as[JsObject]
            val source = KeepSource.keeper
            val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
            if ((info \ "guided").asOpt[Boolean].getOrElse(false)) {
              hcb += ("guided", true)
            }
            implicit val context = hcb.build
            val rawBookmark = info.as[RawBookmarkRepresentation]
            Ok(Json.toJson(keepsCommander.keepOne(rawBookmark, request.userId, libraryId, request.kifiInstallationId, source)))
          case _ =>
            BadRequest(Json.obj("error" -> "invalid_access"))
        }
    }
  }
}
