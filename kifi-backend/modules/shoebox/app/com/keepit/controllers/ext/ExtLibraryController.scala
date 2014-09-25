package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepData, KeepsCommander, LibraryAddRequest, LibraryCommander, LibraryData, RawBookmarkRepresentation, _ }
import com.keepit.common.controller.{ ActionAuthenticator, ShoeboxServiceController, _ }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json._
import play.api.mvc.Result

import scala.util.{ Failure, Success, Try }

class ExtLibraryController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  libraryCommander: LibraryCommander,
  keepsCommander: KeepsCommander,
  basicUserRepo: BasicUserRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  keepImageRepo: KeepImageRepo,
  keepImageRequestRepo: KeepImageRequestRepo,
  keepImageCommander: KeepImageCommander,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getLibraries() = UserAction { request =>
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

  def createLibrary = UserAction(parse.tolerantJson) { request =>
    val body = request.body.as[JsObject]
    val name = (body \ "name").as[String]
    val visibility = (body \ "visibility").as[LibraryVisibility]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name, visibility, description = None, slug, collaborators = None, followers = None)
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
      case Right(lib) =>
        Ok(Json.toJson(LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          visibility = lib.visibility,
          path = Library.formatLibraryPath(request.user.username, request.user.externalId, lib.slug))))
    }
  }

  def addKeep(libraryPubId: PublicId[Library]) = UserAction(parse.tolerantJson) { request =>
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

          // Determine image choice.
          val imageStatus = (info \ "image") match {
            case JsNull => // user purposely wants no image
              Json.obj()
            case JsString(imageUrl) if imageUrl.startsWith("http") =>
              val (keep, keepImageRequest) = db.readWrite { implicit session =>
                val keep = keepRepo.getOpt(keepInfo.id.get).get // Weird pattern, but this should always exist.
                val keepImageRequest = keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = KeepImageSource.UserPicked))
                (keep, keepImageRequest)
              }
              keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, KeepImageSource.UserPicked, Some(keepImageRequest.id.get))
              Json.obj("imageStatusPath" -> routes.ExtKeepImageController.checkImageStatus(libraryPubId, keep.externalId, keepImageRequest.token).url)
            case _ =>
              val keep = db.readOnlyMaster { implicit session =>
                keepRepo.getOpt(keepInfo.id.get).get // Weird pattern, but this should always exist.
              }
              keepImageCommander.autoSetKeepImage(keep.id.get, localOnly = false, overwriteExistingChoice = false)
              Json.obj()
          }

          Ok(Json.toJson(KeepData(
            keepInfo.id.get,
            mine = true, // TODO: stop assuming keep is mine and removable
            removable = true,
            secret = keepInfo.isPrivate,
            libraryId = Library.publicId(libraryId))).as[JsObject] ++ imageStatus)
        case _ =>
          Forbidden(Json.obj("error" -> "invalid_access"))
      }
    }
  }

  // imgSize is of format "<w>x<h>", such as "300x500"
  def getKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], imgSize: Option[String]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
        case Left((status, code)) => Status(status)(Json.obj("error" -> code))
        case Right(keep) =>
          // As we move to using the notion of "keeps" as the entity clients care about,
          // this sort of logic is better suited to be moved to the commander. Until then, and
          // we solidify our use cases, putting it here to keep the commander cleaner.
          val defaultImageSize = ImageSize(700, 500)
          val idealSize = if (imgSize.isEmpty || imgSize.get.length == 0) {
            defaultImageSize
          } else {
            val s = imgSize.get.toLowerCase.split("x").toList
            s match {
              case w :: h :: Nil => Try(ImageSize(w.toInt, h.toInt)).getOrElse(defaultImageSize)
              case _ => defaultImageSize
            }
          }
          val keepImages = db.readOnlyReplica { implicit session =>
            keepImageRepo.getAllForKeepId(keep.id.get)
          }
          val keepImage = KeepImageSize.pickBest(idealSize, keepImages)
          val resp = LateLoadKeepData(keep.title, keepImage.map(keepImageCommander.getUrl))
          Ok(Json.toJson(resp))
      }
    }
  }

  def removeKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      keepsCommander.unkeepOneFromLibrary(keepExtId, libraryId, request.userId) match {
        case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
        case Right(info) => NoContent
      }
    }
  }

  // Maintainers: Let's keep this endpoint simple, quick and reliable. Complex updates deserve their own endpoints.
  def updateKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
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

  private def decode(publicId: PublicId[Library])(action: Id[Library] => Result): Result = {
    Library.decodePublicId(publicId) match {
      case Failure(_) => BadRequest(Json.obj("error" -> "invalid_library_id"))
      case Success(id) => action(id)
    }
  }
}
