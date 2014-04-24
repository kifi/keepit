package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.commanders.LDACommander


class LDAController @Inject()(
  lda: LDACommander
)
extends CortexServiceController {

  def numOfTopics() = Action { request =>
    Ok(JsNumber(lda.numOfTopics))
  }

  def showTopics(fromId: Int, toId: Int, topN: Int) = Action { request =>
    val res = lda.topicWords(fromId, toId, topN).map{case (id, words) => (id.toString, words.toMap)}
    Ok(Json.toJson(res))
  }

}
