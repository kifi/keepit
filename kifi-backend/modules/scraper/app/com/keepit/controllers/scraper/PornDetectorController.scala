package com.keepit.controllers.scraper

import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.common.controller.ScraperServiceController
import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json.Json

class PornDetectorController @Inject()(factory: PornDetectorFactory) extends ScraperServiceController {
  def getModel() = Action { request =>
    Ok(Json.toJson(factory.model.likelihood))
  }
}
