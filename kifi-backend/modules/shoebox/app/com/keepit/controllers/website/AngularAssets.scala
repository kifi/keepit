package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{AnyContent, Action, Controller}
import play.api.Play
import play.api.libs.iteratee.Enumerator
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.logging.Logging


object AngularDistAssets extends AssetsBuilder with Controller with Logging {
  def home = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("angular/index.html").get)) as HTML
  }
}

object AngularImgAssets extends AssetsBuilder with Logging
