package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.commanders.LDACommander
import com.keepit.cortex.features.Document
import com.keepit.cortex.utils.TextUtils


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

  def wordTopic(word: String) = Action { request =>
    val res = lda.wordTopic(word)
    Ok(Json.toJson(res))
  }

  def docTopic() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val doc = (js \ "doc").as[String]
    val wrappedDoc = Document(TextUtils.TextTokenizer.LowerCaseTokenizer.tokenize(doc))
    val res = lda.docTopic(wrappedDoc)
    Ok(Json.toJson(res))
  }

}
