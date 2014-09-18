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

    (startUserId to endUserId).map { userLong =>
      val userId = Id[User](userLong)
      val libraryIds = db.readOnlyReplica { implicit session =>
        libraryRepo.getByUser(userId).map(_._2.id.get)
      }
      libraryIds.map { libraryId =>
        val keepCount = keepRepo.getCountByLibrary(libraryId)
        (0 to keepCount by 1000).map { batchStart =>
          val keepIds = keepRepo.getByLibrary(libraryId, 1000, batchStart).map(_.id.get)
          keepIds.map { keepId =>
            keepImageCommander.autoSetKeepImage(keepId, localOnly = true, overwriteExistingChoice = false)
          }
        }
      }
    }



    val keeps = db.readOnlyReplica { implicit session =>
      keepRepo.getByLibrary(libraryId, take, drop)
    }

    val process = FutureHelpers.foldLeft(keeps)("") {
      case (resp, keep) =>
        keepImageCommander.autoSetKeepImage(keep.id.get, localOnly = true, overwriteExistingChoice = false).map { s =>
          resp + "\n" + s.toString
        }
    }
    process.map(c => Ok(c))
  }

}
