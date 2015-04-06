package com.keepit.controllers.scraper

import com.keepit.common.controller.ScraperServiceController
import com.google.inject.Inject
import com.keepit.rover.sensitivity.{ PornDetectorFactory, PornDetectorUtil, PornWordLikelihood, PornWordLikelihoodStore }
import play.api.mvc.Action
import play.api.libs.json._
import scala.collection.mutable

class PornDetectorController @Inject() (
    factory: PornDetectorFactory,
    store: PornWordLikelihoodStore) extends ScraperServiceController {
  val FILE_NAME = "ratioMap"

  def getModel() = Action { request =>
    Ok(Json.toJson(factory.model.likelihood))
  }

  def detect() = Action(parse.tolerantJson) { request =>
    val query = (request.body \ "query").as[String]
    val detector = factory.naiveBayes()
    val windows = PornDetectorUtil.tokenize(query).sliding(10, 10)
    val badTexts = windows.map { w => val block = w.mkString(" "); (block, detector.posterior(block)) }.filter(_._2 > 0.5f)
    Ok(Json.toJson(badTexts.toMap))
  }

  def whitelist() = Action(parse.tolerantJson) { request =>
    val whitelist = (request.body \ "whitelist").as[String]
    val tokens = PornDetectorUtil.tokenize(whitelist)
    val model = store.syncGet(FILE_NAME).get
    val likelihood = mutable.Map() ++ model.likelihood
    var cleaned = ""
    tokens.foreach { t =>
      if (likelihood.contains(t) && likelihood(t) > 1f) {
        likelihood(t) = 1f
        cleaned += t + " "
      }
    }
    val newModel = PornWordLikelihood(likelihood.toMap)
    store += (FILE_NAME, newModel)
    Ok(Json.toJson(cleaned))
  }
}
