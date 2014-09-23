package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.{ Result, Action, Controller }

import scala.concurrent.Future

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
    val (keepOpt, libOpt) = db.readOnlyMaster { implicit session =>
      val k = keepRepo.getOpt(keepExtId)
      val lib = Library.decodePublicId(libraryPubId).toOption.flatMap(libId => keepRepo.getByExtIdandLibraryId(keepExtId, libId))
      (k, lib)
    }
    (keepOpt, libOpt) match {
      case (_, None) =>
        Future.successful(NotFound(Json.obj("error" -> "keep_not_found")))
      case (Some(keep), _) =>
        val imageRequest = db.readWrite { implicit session =>
          keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = KeepImageSource.UserUpload))
        }
        val setImageF = keepImageCommander.setKeepImageFromFile(request.body, keep.id.get, KeepImageSource.UserUpload, Some(imageRequest.id.get))
        setImageF.map {
          case fail: KeepImageStoreFailure =>
            InternalServerError(Json.obj("error" -> fail.reason))
          case success: ImageProcessSuccess =>
            Ok(JsString("success"))
        }
      case (None, _) =>
        Future.successful(NotFound(Json.obj("error" -> "invalid_keep_id")))
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
            val totalKeepInLibraryCount = db.readOnlyReplica(keepRepo.getCountByLibrary(libraryId)(_))
            val batchPositions = 0 to totalKeepInLibraryCount by 1000
            FutureHelpers.foldLeft(batchPositions)(0) {
              case (batchKeepCount, batchPosition) =>
                val keepIds = db.readOnlyReplica(keepRepo.getByLibrary(libraryId, 1000, batchPosition)(_)).map(_.id.get)
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
