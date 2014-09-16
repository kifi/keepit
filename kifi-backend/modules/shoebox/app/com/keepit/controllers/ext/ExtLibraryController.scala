package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepData, KeepsCommander, RawBookmarkRepresentation, LibraryCommander, LibraryData }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.{ LibraryMembershipRepo, Keep, KeepSource, Library, LibraryAccess }
import com.keepit.social.BasicUser
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
    val basicSelf = BasicUser.fromUser(request.user)
    val datas = libraryCommander.getLibrariesUserCanKeepTo(request.userId) map { lib =>
      val owner = if (lib.ownerId == request.userId)
        basicSelf
      else
        db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
      LibraryData(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        visibility = lib.visibility,
        path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug))
    }
    Ok(Json.obj("libraries" -> datas))
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
            Forbidden
        }
    }
  }

  def removeKeep(pubId: PublicId[Library], keepExtId: ExternalId[Keep]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_library_id"))
      case Success(libraryId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
        keepsCommander.unkeepFromLibrary(Seq(keepExtId), libraryId, request.userId) match {
          case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
          case Right(_) => NoContent
        }
    }
  }
}
