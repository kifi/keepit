package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.store.ImageSize
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext
import scala.util.Try

class LibraryImageController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryImageRepo: LibraryImageRepo,
  libraryImageRequestRepo: LibraryImageRequestRepo,
  fortyTwoConfig: FortyTwoConfig,
  clock: Clock,
  libraryImageCommander: LibraryImageCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  val userActionsHelper: UserActionsHelper,
  val libraryInfoCommander: LibraryInfoCommander,
  val libraryAccessCommander: LibraryAccessCommander,
  implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val executionContext: ExecutionContext)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  def uploadLibraryImage(pubId: PublicId[Library], imageSize: Option[String] = None, posX: Option[Int] = None, posY: Option[Int] = None) = (UserAction andThen LibraryOwnerAction(pubId)).async(parse.temporaryFile) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val imageRequest = db.readWrite { implicit session =>
      libraryImageRequestRepo.save(LibraryImageRequest(libraryId = libraryId, source = ImageSource.UserUpload))
    }
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
    val uploadImageF = libraryImageCommander.uploadLibraryImageFromFile(request.body.file, libraryId, LibraryImagePosition(posX, posY), ImageSource.UserUpload, request.userId, Some(imageRequest.id.get)).map { s =>
      request.body.file.delete()
      s
    }
    uploadImageF.map {
      case fail: ImageStoreFailure =>
        InternalServerError(Json.obj("error" -> fail.reason))
      case success: ImageProcessSuccess =>
        val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(LibraryImageController.defaultImageSize)
        db.readOnlyMaster(implicit s => libraryImageCommander.getBestImageForLibrary(libraryId, idealSize)) match {
          case Some(img: LibraryImage) =>
            Ok(Json.toJson(img.asInfo))
          case None =>
            NotFound(Json.obj("error" -> "image_not_found"))
        }
    }
  }

  def positionLibraryImage(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val imagePath = (request.body \ "path").as[String]
    val posX = (request.body \ "x").asOpt[Int]
    val posY = (request.body \ "y").asOpt[Int]

    imagePath match {
      case LibraryImageController.pathHashRe(hashStr) =>
        val currentHash = db.readOnlyMaster { implicit s =>
          libraryImageRepo.getActiveForLibraryId(libraryId)
        }.map(_.sourceFileHash).toSet
        if (currentHash.contains(ImageHash(hashStr))) {
          libraryImageCommander.positionLibraryImage(libraryId, LibraryImagePosition(posX, posY))
          NoContent
        } else {
          BadRequest(Json.obj("error" -> "incorrect_image"))
        }
      case _ =>
        BadRequest(Json.obj("error" -> "invalid_image_path"))
    }
  }

  def removeLibraryImage(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId)) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
    libraryImageCommander.removeImageForLibrary(libraryId, request.userId)
    NoContent
  }

  def getLibraryImages(idStrings: String, idealSize: Option[String]) = MaybeUserAction { request =>
    val libIds = idStrings.split('.').flatMap(id => Library.decodePublicId(PublicId[Library](id)).toOption)
    if (libIds.nonEmpty) {
      val accessibleLibIds = db.readOnlyReplica { implicit session =>
        libraryRepo.getActiveByIds(libIds.toSet)
      } filter {
        case (_, lib) => libraryAccessCommander.canViewLibrary(request.userIdOpt, lib)
      } keySet
      val size = idealSize.map(ImageSize(_)).getOrElse(ProcessedImageSize.Medium.idealSize)
      val imagesByPublicId = db.readOnlyMaster(implicit s => libraryImageCommander.getBestImageForLibraries(accessibleLibIds, size)) map {
        case (id, img) => Library.publicId(id).id -> img.asInfo
      }
      Ok(Json.toJson(imagesByPublicId))
    } else {
      BadRequest(Json.obj("error" -> "invalid_ids"))
    }
  }
}

object LibraryImageController {
  val defaultImageSize = ProcessedImageSize.XLarge.idealSize
  val pathHashRe = "library/([^_]+)_.*".r
}
