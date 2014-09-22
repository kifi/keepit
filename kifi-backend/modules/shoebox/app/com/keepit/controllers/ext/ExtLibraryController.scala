package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepData, KeepsCommander, RawBookmarkRepresentation, LibraryCommander, LibraryData, LibraryAddRequest }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.{ Keep, KeepSource, Library, LibraryAccess, LibraryMembershipRepo, LibraryVisibility, LibrarySlug }

import play.api.libs.json._
import play.api.mvc.Result

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
    val datas = libraryCommander.getLibrariesUserCanKeepTo(request.userId) map { lib =>
      val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
      LibraryData(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        visibility = lib.visibility,
        path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug))
    }
    Ok(Json.obj("libraries" -> datas))
  }

  def addKeep(libraryPubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    decode(libraryPubId) { libraryId =>
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
          val keepInfo = keepsCommander.keepOne(rawBookmark, request.userId, libraryId, request.kifiInstallationId, source)
          Ok(Json.toJson(KeepData(
            keepInfo.id.get,
            mine = true, // TODO: stop assuming keep is mine and removable
            removable = true,
            secret = keepInfo.isPrivate,
            libraryId = Library.publicId(libraryId))))
        case _ =>
          Forbidden(Json.obj("error" -> "invalid_access"))
      }
    }
  }

  def getKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = JsonAction.authenticated { request =>
    decode(libraryPubId) { libraryId =>
      keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
        case Left((status, code)) => Status(status)(Json.obj("error" -> code))
        case Right(keep) => Ok(Json.obj("title" -> keep.title))
      }
    }
  }

  def removeKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = JsonAction.authenticated { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      keepsCommander.unkeepOneFromLibrary(keepExtId, libraryId, request.userId) match {
        case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
        case Right(info) => NoContent
      }
    }
  }

  // Maintainers: Let's keep this endpoint simple, quick and reliable. Complex updates deserve their own endpoints.
  def updateKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = JsonAction.authenticatedParseJson { request =>
    decode(libraryPubId) { libraryId =>
      val body = request.body.as[JsObject]
      val title = (body \ "title").asOpt[String]
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      keepsCommander.updateKeepInLibrary(keepExtId, libraryId, request.userId, title) match {
        case Left((status, code)) => Status(status)(Json.obj("error" -> code))
        case Right(keep) => NoContent
      }
    }
  }

  def addLibrary = JsonAction.authenticatedParseJson { request =>
    val body = request.body.as[JsObject]
    val name = (body \ "name").as[String]
    val visibility = (body \ "visibility").as[LibraryVisibility]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name, visibility, description = None, slug, collaborators = None, followers = None)
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(request.userId) }
        Ok(Json.toJson(LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          visibility = lib.visibility,
          path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug))))
    }
  }

  private def decode(publicId: PublicId[Library])(action: Id[Library] => Result): Result = {
    Library.decodePublicId(publicId) match {
      case Failure(_) => BadRequest(Json.obj("error" -> "invalid_library_id"))
      case Success(id) => action(id)
    }
  }
}
