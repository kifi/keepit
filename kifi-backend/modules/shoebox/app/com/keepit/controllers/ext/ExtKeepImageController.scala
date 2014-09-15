package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{KeepImageCommander, KeepImageSize}
import com.keepit.common.db.Id
import com.keepit.model.{Keep, KeepImageSource}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{Action, Controller}

class ExtKeepImageController @Inject() (keepImageCommander: KeepImageCommander)
    extends Controller {

  def autoFetch() = Action.async(parse.tolerantJson) { request =>
    val keepId = (request.body \ "keepId").as[Id[Keep]]
    keepImageCommander.autoSetKeepImage(keepId, overwriteExistingChoice = true).map { result =>
      Ok(result.toString)
    }
  }

  def setImage() = Action.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    val keepId = (request.body \ "keepId").as[Id[Keep]]
    keepImageCommander.setKeepImage(url, keepId, KeepImageSource.UserPicked).map { result =>
      Ok(result.toString)
    }
  }

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

}
