package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.net.URI
import com.keepit.common.store.ImageSize
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, JsString, Json }
import play.api.mvc.{ Result, Action, Controller }

import scala.concurrent.Future
import scala.util.Try

class ExtKeepImageController @Inject() (
    keepImageCommander: KeepImageCommander,
    keepRepo: KeepRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    keepImageRequestRepo: KeepImageRequestRepo,
    db: Database,
    systemValueRepo: SystemValueRepo,
    imageInfoRepo: ImageInfoRepo,
    val userActionsHelper: UserActionsHelper,
    implicit val config: com.keepit.common.crypto.PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def uploadKeepImage(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction.async(parse.temporaryFile) { request =>
    val keepOpt = db.readOnlyMaster { implicit session =>
      Library.decodePublicId(libraryPubId).toOption.flatMap(libId => keepRepo.getByExtIdandLibraryId(keepExtId, libId))
    }
    keepOpt match {
      case None =>
        Future.successful(NotFound(Json.obj("error" -> "keep_not_found")))
      case Some(keep) =>
        val imageRequest = db.readWrite { implicit session =>
          keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = ImageSource.UserUpload))
        }
        val setImageF = keepImageCommander.setKeepImageFromFile(request.body, keep.id.get, ImageSource.UserUpload, Some(imageRequest.id.get))
        setImageF.map {
          case fail: ImageStoreFailure =>
            InternalServerError(Json.obj("error" -> fail.reason))
          case success: ImageProcessSuccess =>
            Ok(JsString("success"))
        }
    }
  }

  def checkImageStatus(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], token: String) = UserAction.async { request =>
    def checkStatus() = {
      import KeepImageRequestStates._
      val (imageRequestOpt, keepOpt) = db.readOnlyReplica { implicit session =>
        val img = keepImageRequestRepo.getByToken(token)
        val k = Library.decodePublicId(libraryPubId).toOption.flatMap(libId => keepRepo.getByExtIdandLibraryId(keepExtId, libId))
        (img, k)
      }
      (keepOpt, imageRequestOpt) match {
        case (None, _) => Some(NotFound(Json.obj("error" -> "keep_not_found")))
        case (_, None) =>
          Some(NotFound(Json.obj("error" -> "token_not_found")))
        case (_, Some(imageRequest)) if imageRequest.state == KeepImageRequestStates.INACTIVE => // success
          Some(Ok(JsString("success")))
        case (_, Some(imageRequest)) if Set(ACTIVE, FETCHING, PERSISTING, PROCESSING).contains(imageRequest.state) => // in progress
          None
        case (_, Some(imageRequest)) => // failure
          Some(Ok(Json.obj("error" -> imageRequest.failureCode)))
      }
    }

    var times = 0
    def timeoutF = play.api.libs.concurrent.Promise.timeout(None, 500)
    def pollCheck(): Future[Result] = {
      timeoutF.flatMap { _ =>
        checkStatus() match {
          case None if times < 15 =>
            times += 1
            pollCheck()
          case None => Future.successful(Ok(Json.obj("error" -> "token_not_found")))
          case Some(result) => Future.successful(result)
        }
      }
    }

    checkStatus().map(Future.successful).getOrElse(pollCheck())
  }

  def changeKeepImage(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], size: Option[String]) = UserAction.async(parse.tolerantJson) { request =>
    db.readOnlyMaster { implicit session =>
      Library.decodePublicId(libraryPubId).toOption.flatMap(libId => keepRepo.getByExtIdandLibraryId(keepExtId, libId))
    } map { keep =>
      (request.body \ "image") match {
        case JsNull =>
          keepImageCommander.removeKeepImageForKeep(keep.id.get)
          Future.successful(Ok(Json.obj("image" -> JsNull)))
        case JsString(imageUrl @ URI(scheme, _, _, _, _, _, _)) if scheme.exists(_.startsWith("http")) =>
          val imageRequest = db.readWrite { implicit session =>
            keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = ImageSource.UserUpload))
          }
          keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, ImageSource.UserUpload, imageRequest.id) map {
            case fail: ImageStoreFailure =>
              InternalServerError(Json.obj("error" -> fail.reason))
            case _: ImageProcessSuccess =>
              val idealSize = size.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(ExtLibraryController.defaultImageSize)
              Ok(Json.obj("image" -> keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(idealSize)).flatten.map(keepImageCommander.getUrl)))
          }
        case JsString(badUrl) =>
          log.info(s"rejecting image url: $badUrl")
          Future.successful(BadRequest(Json.obj("error" -> "bad_image_url")))
        case _ =>
          Future.successful(BadRequest(Json.obj("error" -> "no_image_url")))
      }
    } getOrElse {
      Future.successful(NotFound(Json.obj("error" -> "keep_not_found")))
    }
  }

  // migration
  def loadPrevImageForKeep(startUserId: Long, endUserId: Long) = Action.async { request =>

    val users = (startUserId to endUserId).map(Id[User])

    val process = FutureHelpers.foldLeft(users)(0) {
      case (userCount, userId) =>
        val libraryIds = db.readOnlyReplica { implicit session =>
          libraryRepo.getByUser(userId).map(_._2.id.get)
        }
        FutureHelpers.foldLeft(libraryIds)(0) {
          case (libraryCount, libraryId) =>
            val lib = db.readOnlyMaster(implicit s => libraryRepo.get(libraryId))
            val batchPositions = 0 to lib.keepCount by 1000
            FutureHelpers.foldLeft(batchPositions)(0) {
              case (batchKeepCount, batchPosition) =>
                val keepIds = db.readOnlyReplica(keepRepo.getByLibrary(libraryId, batchPosition, 1000)(_)).map(_.id.get)
                FutureHelpers.foldLeft(keepIds)(0) {
                  case (keepCount, keepId) =>
                    keepImageCommander.autoSetKeepImage(keepId, localOnly = true, overwriteExistingChoice = false).map { s =>
                      keepCount + 1
                    }
                }.map { result =>
                  log.info(s"[kiip] Finished u:$userId, l:$libraryId, b:$batchPosition / ${batchPositions.length}, k: $result")
                  db.readWrite { implicit session =>
                    systemValueRepo.setValue(Name("keep_image_import_progress"), s"u:$userId, l:$libraryId, b:$batchPosition / ${batchPositions.length}, k: $result")
                  }
                  result
                }
            }
        }
    }

    process.map(c => Ok(c.toString))
  }

}
