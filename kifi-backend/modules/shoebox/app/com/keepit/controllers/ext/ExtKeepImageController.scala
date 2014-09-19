package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepImageCommander, KeepImageSize }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{ Action, Controller }

class ExtKeepImageController @Inject() (
    keepImageCommander: KeepImageCommander,
    keepRepo: KeepRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    db: Database,
    imageInfoRepo: ImageInfoRepo) extends ShoeboxServiceController {

  // unused, for reference
  def autoFetch() = Action.async(parse.tolerantJson) { request =>
    val keepId = (request.body \ "keepId").as[Id[Keep]]
    keepImageCommander.autoSetKeepImage(keepId, overwriteExistingChoice = true).map { result =>
      Ok(result.toString)
    }
  }

  // unused, for reference
  def setImage() = Action.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    val keepId = (request.body \ "keepId").as[Id[Keep]]
    keepImageCommander.setKeepImage(url, keepId, KeepImageSource.UserPicked).map { result =>
      Ok(result.toString)
    }
  }

  // unused, for reference
  def getBestImageForKeep() = Action(parse.tolerantJson) { request =>
    val sizePref = (request.body \ "size").as[String]
    val sizeOpt = KeepImageSize.imageSizeFromString(sizePref)
    val keepId = (request.body \ "keepId").as[Id[Keep]]
    sizeOpt match {
      case Some(size) =>
        keepImageCommander.getBestImageForKeep(keepId, size).map { result =>
          Ok(result.toString)
        }.getOrElse(Ok("no image"))
      case None =>
        BadRequest("bad size")
    }
  }

  def loadPrevImageForKeep(startUserId: Long, endUserId: Long, take: Int, drop: Int) = Action.async { request =>

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
                  result
                }
            }
        }
    }

    process.map(c => Ok(c.toString))
  }

}
