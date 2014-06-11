package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{AnyContent, Action, Controller}
import play.api.Play
import play.api.libs.iteratee.Enumerator
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.logging.Logging
import play.utils.UriEncoding
import java.io.File
import com.keepit.model.ExperimentType


object AngularDistAssets extends AssetsBuilder with Controller with Logging {
  def home = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("angular/index.html").get)) as HTML
  }
  override def at(path: String, file: String): Action[AnyContent] = Action.async { request =>
    if (request.session.get("preview").nonEmpty && request.domain.startsWith("preview.")) {
      super.at(path.replaceFirst("angular", "angular-black"), file).apply(request)
    } else {
      super.at(path, file).apply(request)
    }
  }
}
object AngularImgAssets extends AssetsBuilder with Logging {
  override def at(path: String, file: String): Action[AnyContent] = Action.async { request =>
    if (request.session.get("preview").nonEmpty && request.domain.startsWith("preview.")) {
      super.at(path.replaceFirst("angular", "angular-black"), file).apply(request)
    } else {
      super.at(path, file).apply(request)
    }
  }
}
