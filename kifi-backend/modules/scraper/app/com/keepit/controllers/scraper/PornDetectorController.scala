package com.keepit.controllers.scraper

import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.common.controller.ScraperServiceController
import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.learning.porndetector.PornDetectorUtil

class PornDetectorController @Inject()(factory: PornDetectorFactory) extends ScraperServiceController {
  def getModel() = Action { request =>
    Ok(Json.toJson(factory.model.likelihood))
  }

  def detect(query: String) = Action { request =>
    val detector = factory()
    val windows = PornDetectorUtil.tokenize(query).sliding(8, 4)
    val badTexts = windows.map{ w => val block = w.mkString(" "); (block, detector.posterior(block)) }.filter(_._2 > 0.5f)
    Ok(Json.toJson(badTexts.toMap))
  }
}
