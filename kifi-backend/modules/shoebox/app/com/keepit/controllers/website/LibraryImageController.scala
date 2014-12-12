package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander, LibraryImageCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.store.ImageSize
import com.keepit.common.time.Clock
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.json.{ Json }

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.Try

class LibraryImageController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryImageRepo: LibraryImageRepo,
  libraryImageRequestRepo: LibraryImageRequestRepo,
  userRepo: UserRepo,
  fortyTwoConfig: FortyTwoConfig,
  clock: Clock,
  libraryImageCommander: LibraryImageCommander,
  val userActionsHelper: UserActionsHelper,
  val libraryCommander: LibraryCommander,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  def uploadLibraryImage(pubId: PublicId[Library], imageSize: Option[String] = None, posX: Option[Int] = None, posY: Option[Int] = None) = (UserAction andThen LibraryWriteAction(pubId)).async(parse.temporaryFile) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val imageRequest = db.readWrite { implicit session =>
      libraryImageRequestRepo.save(LibraryImageRequest(libraryId = libraryId, source = ImageSource.UserUpload))
    }
    val position = LibraryImagePosition(posX, posY)
    val uploadImageF = libraryImageCommander.uploadLibraryImageFromFile(request.body, libraryId, position, ImageSource.UserUpload, Some(imageRequest.id.get))
    uploadImageF.map {
      case fail: ImageStoreFailure =>
        InternalServerError(Json.obj("error" -> fail.reason))
      case success: ImageProcessSuccess =>
        val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(LibraryImageController.defaultImageSize)
        libraryImageCommander.getBestImageForLibrary(libraryId, idealSize) match {
          case Some(img) =>
            Ok(Json.obj("imagePath" -> img.imagePath))
          case None =>
            NotFound(Json.obj("error" -> "image_not_found"))
        }
    }
  }

  def positionLibraryImage(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val imagePath = (request.body \ "imagePath").as[String]
    val posX = (request.body \ "x").asOpt[Int]
    val posY = (request.body \ "y").asOpt[Int]

    imagePath match {
      case LibraryImageController.pathRegex(hashStr) =>
        val currentHash = db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(libraryId)
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

  def removeLibraryImage(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryImageCommander.removeImageForLibrary(libraryId)
    NoContent
  }
}

object LibraryImageController {
  val defaultImageSize = ImageSize(600, 480)
  val pathRegex = """^library/(.*?)_\d+x\d+.+""".r
}
