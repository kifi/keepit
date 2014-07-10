package com.keepit.controllers.cortex

import com.google.inject.Inject
import com.keepit.common.commanders.Word2VecCommander
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import play.api.libs.concurrent.Execution.Implicits._

class CortexController @Inject() (
    word2vec: Word2VecCommander) extends CortexServiceController {

  def similarity(word1: String, word2: String) = Action { request =>
    val s = word2vec.similarity(word1, word2)
    Ok(Json.toJson(s))
  }

  def uriSimilarity(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]) = Action { request =>
    val s = word2vec.similarity(uri1, uri2)
    Ok(Json.toJson(s))
  }

  def userSimilarity() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val uris1 = (js \ "uris1").as[JsArray].value.map { x => Id[NormalizedURI](x.as[Long]) }
    val uris2 = (js \ "uris2").as[JsArray].value.map { x => Id[NormalizedURI](x.as[Long]) }
    val s = word2vec.userSimilarity2(uris1, uris2)
    Ok(Json.toJson(s))
  }

  def queryUriSimilarity() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val query = (js \ "query").as[String]
    val uri = Id[NormalizedURI]((js \ "uri").as[Long])
    val s = word2vec.similarity(query, uri)
    Ok(Json.toJson(s))
  }

  def userUriSimilarity() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userUris = (js \ "userUris").as[JsArray].value.map { x => Id[NormalizedURI](x.as[Long]) }
    val uri = Id[NormalizedURI]((js \ "uri").as[Long])
    val m = word2vec.userUriSimilarity(userUris, uri).map { case (id, score) => (id.toString, score) }
    Ok(Json.toJson(m))
  }

  def feedUserUris() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userUris = (js \ "userUris").as[JsArray].value.map { x => Id[NormalizedURI](x.as[Long]) }
    val feedUris = (js \ "feedUris").as[JsArray].value.map { x => Id[NormalizedURI](x.as[Long]) }
    val filtered = word2vec.feedUserUri(userUris, feedUris)
    Ok(Json.toJson(filtered))
  }

  def getKeywordsAndBOW() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val text = (js \ "query").as[String]
    val resOpt = word2vec.getDoc2VecResult(text)
    val rv = resOpt match {
      case None => Map("keywords" -> "N/A", "bow" -> "N/A")
      case Some(res) => Map("keywords" -> res.keywords.mkString(", "), "bow" -> res.bagOfWords.toArray.sortBy(-1 * _._2).mkString(", "))
    }
    Ok(Json.toJson(rv))
  }

  def getURIKeywords(uri: Id[NormalizedURI]) = Action { request =>
    val key = word2vec.uriKeywords(uri)
    Ok(Json.toJson(key))
  }

  def batchGetURIKeywords = Action.async(parse.tolerantJson) { request =>
    val uris = request.body.as[Seq[Id[NormalizedURI]]]
    word2vec.batchURIKeywords(uris).map { keys =>
      Ok(Json.toJson(keys))
    }
  }

}
