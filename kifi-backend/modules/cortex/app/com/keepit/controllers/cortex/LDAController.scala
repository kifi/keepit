package com.keepit.controllers.cortex

import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.commanders.{ LDAInfoCommander, LDACommander }
import com.keepit.cortex.features.Document
import com.keepit.cortex.utils.TextUtils
import com.keepit.cortex.models.lda.{ LDAUserURIInterestScores, LDATopicConfigurations, LDATopicConfiguration, LDATopicInfo }
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.db.Id

class LDAController @Inject() (
  lda: LDACommander,
  infoCommander: LDAInfoCommander)
    extends CortexServiceController {

  def numOfTopics() = Action { request =>
    Ok(JsNumber(lda.numOfTopics))
  }

  def showTopics(fromId: Int, toId: Int, topN: Int) = Action { request =>
    val topicWords = infoCommander.topicWords(fromId, toId, topN).map { case (id, words) => (id.toString, words.toMap) }
    val topicConfigs = infoCommander.topicConfigs(fromId, toId)
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
    Ok(Json.toJson(infoCommander.ldaConfigurations))
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
    val scores = lda.gaussianUserUriInterest(userId, uriId)
    Ok(Json.toJson(scores))
  }

  def batchUserURIsInterests() = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userId = (js \ "userId").as[Id[User]]
    val uriIds = (js \ "uriIds").as[Seq[Id[NormalizedURI]]]
    val scores = lda.batchUserURIsInterests(userId, uriIds)
    //    val scores1 = lda.batchUserURIsInterests(userId, uriIds)
    //    val scores2 = lda.batchGaussianUserURIsInterests(userId, uriIds)
    //    val scores = (scores1 zip scores2).map { case (s1, s2) => LDAUserURIInterestScores(s2.global, s1.recency) }
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

  def dumpScaledUserInterest(userId: Id[User]) = Action { request =>
    val vec = lda.dumpScaledUserInterest(userId)
    Ok(Json.toJson(vec))
  }

  def userSimilarity(userId1: Id[User], userId2: Id[User]) = Action { request =>
    val score = lda.userSimilairty(userId1, userId2)
    Ok(Json.toJson(score))
  }

}
