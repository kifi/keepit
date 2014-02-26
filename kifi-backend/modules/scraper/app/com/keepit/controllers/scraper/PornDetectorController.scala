package com.keepit.controllers.scraper

import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.common.controller.ScraperServiceController
import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._

class PornDetectorController @Inject()(factory: PornDetectorFactory) extends ScraperServiceController {
  def getModel() = Action { request =>
    Ok(Json.toJson(factory.model.likelihood))
  }

  def detect(query: String) = Action { request =>
    val detector = factory()
    val prob = detector.posterior(query)
    val badTexts = if (prob > 0.5f) Map(query -> prob) else Map.empty[String, Float]    // will change this when we have sliding window detector
    Ok(Json.toJson(badTexts))
  }
}
