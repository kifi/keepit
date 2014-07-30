package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.commanders.LDACommander
import com.keepit.cortex.features.Document
import com.keepit.cortex.utils.TextUtils
import com.keepit.cortex.models.lda.{ LDATopicConfigurations, LDATopicConfiguration, LDATopicInfo }
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.db.Id

class LDAController @Inject() (
  lda: LDACommander)
    extends CortexServiceController {

  def numOfTopics() = Action { request =>
    Ok(JsNumber(lda.numOfTopics))
  }

  def showTopics(fromId: Int, toId: Int, topN: Int) = Action { request =>
    val topicWords = lda.topicWords(fromId, toId, topN).map { case (id, words) => (id.toString, words.toMap) }
    val topicConfigs = lda.topicConfigs(fromId, toId)
    val infos = topicWords.map {
      case (tid, words) =>
        val config = topicConfigs(tid)
        LDATopicInfo(tid.toInt, words, config)
    }.toArray.sortBy(x => x.topicId)
    Ok(Json.toJson(infos))
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

  def saveEdits() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val configs = js.as[Map[String, LDATopicConfiguration]]
    lda.saveConfigEdits(configs)
    Ok
  }

  def ldaConfigurations = Action { request =>
    Ok(Json.toJson(lda.ldaConfigurations))
  }

  def getLDAFeatures() = Action.async(parse.tolerantJson) { request =>
    implicit val format = Id.format[NormalizedURI]
    val ids = (request.body).as[Seq[Id[NormalizedURI]]]
    Future {
      val feats = lda.getLDAFeatures(ids)
      val vecs = feats.flatMap { featOpt => featOpt.map { _.vectorize } }
      Ok(Json.toJson(vecs))
    }
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    val scores = lda.userUriInterest(userId, uriId)
    Ok(Json.toJson(scores))
  }

  def batchUserURIsInterests() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userId = (js \ "userId").as[Id[User]]
    val uriIds = (js \ "uriIds").as[Seq[Id[NormalizedURI]]]
    val scores = lda.batchUserURIsInterests(userId, uriIds)
    Ok(Json.toJson(scores))
  }

  def userTopicMean(userId: Id[User]) = Action { request =>
    val meanOpt = lda.userTopicMean(userId)
    Ok(Json.toJson(meanOpt.map { _.mean }))
  }

  def sampleURIs(topicId: Int) = Action { request =>
    val uris = lda.sampleURIs(topicId)
    Ok(Json.toJson(uris))
  }

  def getSimilarUsers(userId: Id[User], topK: Int) = Action { request =>
    val (ids, scores) = lda.getSimilarUsers(userId, topK)
    Ok(Json.obj("userIds" -> ids, "scores" -> scores))
  }

}
