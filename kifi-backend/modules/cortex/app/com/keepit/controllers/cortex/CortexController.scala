package com.keepit.controllers.cortex

import com.google.inject.Inject
import com.keepit.common.commanders.Word2VecCommander
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI



class CortexController @Inject()(
  word2vec: Word2VecCommander
) extends CortexServiceController {

  def similarity(word1: String, word2: String) = Action { request =>
    val s = word2vec.similarity(word1, word2)
    Ok(Json.toJson(s))
  }

  def uriSimilarity(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]) = Action { request =>
    val s = word2vec.similarity(uri1, uri2)
    Ok(Json.toJson(s))
  }

  def getKeywordsAndBOW() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val text = (js \ "query").as[String]
    val resOpt = word2vec.getDoc2VecResult(text)
    val rv = resOpt match {
      case None => Map("keywords" -> "N/A", "bow" -> "N/A")
      case Some(res) => Map("keywords" -> res.keywords.mkString(", "), "bow" -> res.bagOfWords.mkString(", ") )
    }
    Ok(Json.toJson(rv))
  }

}
