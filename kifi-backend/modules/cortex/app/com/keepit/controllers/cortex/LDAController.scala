package com.keepit.controllers.cortex

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.cortex.PublishingVersions
import com.keepit.cortex.core.{ CortexVersionCommander, ModelVersion }
import com.keepit.macros.MonitoredAwaitMacro
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.common.controller.CortexServiceController
import com.keepit.common.commanders.{ LDARepresenterCommander, LDAInfoCommander, LDACommander }
import com.keepit.cortex.features.Document
import com.keepit.cortex.utils.TextUtils
import com.keepit.cortex.models.lda._
import com.keepit.model.{ Library, User, NormalizedURI }
import com.keepit.common.db.Id

import scala.concurrent.Await
import scala.concurrent.duration._

class LDAController @Inject() (
  lda: LDACommander,
  versionCommander: CortexVersionCommander,
  representer: LDARepresenterCommander,
  infoCommander: LDAInfoCommander)
    extends CortexServiceController with Logging {

  private val defaultVersion = PublishingVersions.denseLDAVersion

  implicit def toVersion(implicit version: Option[Int]): ModelVersion[DenseLDA] = version.map { ModelVersion[DenseLDA](_) } getOrElse defaultVersion

  private def getVersionForUser(versionOpt: Option[ModelVersion[DenseLDA]], userIdOpt: Option[Id[User]]): ModelVersion[DenseLDA] = {
    (versionOpt, userIdOpt) match {
      case (Some(v), _) => v
      case (None, Some(uid)) => Await.result(versionCommander.getLDAVersionForUser(uid), 1 second) // use of Await is temp. (Only during model experimenting)
      case (None, None) => defaultVersion
    }
  }

  def numOfTopics(implicit version: Option[Int]) = Action { request =>
    Ok(JsNumber(lda.numOfTopics))
  }

  def showTopics(fromId: Int, toId: Int, topN: Int, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val topicWords = infoCommander.topicWords(fromId, toId, topN).map { case (id, words) => (id.toString, words.toMap) }
    val topicConfigs = infoCommander.topicConfigs(fromId, toId)
    val infos = topicWords.map {
      case (tid, words) =>
        val config = topicConfigs(tid)
        LDATopicInfo(tid.toInt, words, config)
    }.toArray.sortBy(x => x.topicId)
    Ok(Json.toJson(infos))
  }

  def wordTopic(word: String, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val res = representer.wordTopic(word)
    Ok(Json.toJson(res))
  }

  def docTopic(implicit version: Option[Int]) = Action(parse.tolerantJson) { request =>
    val js = request.body
    val doc = (js \ "doc").as[String]
    val wrappedDoc = Document(TextUtils.TextTokenizer.LowerCaseTokenizer.tokenize(doc))
    val res = representer.docTopic(wrappedDoc)
    Ok(Json.toJson(res))
  }

  def saveEdits(implicit version: Option[Int]) = Action(parse.tolerantJson) { request =>
    val js = request.body
    val configs = js.as[Map[String, LDATopicConfiguration]]
    infoCommander.saveConfigEdits(configs)
    Ok
  }

  def ldaConfigurations(implicit version: Option[Int]) = Action { request =>
    Ok(Json.toJson(infoCommander.ldaConfigurations))
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val scores1 = lda.userUriInterest(userId, uriId)
    val scores2 = lda.gaussianUserUriInterest(userId, uriId)
    val score3 = lda.libraryInducedUserURIInterest(userId, uriId)
    Ok(Json.toJson(LDAUserURIInterestScores(scores2.global, scores1.recency, score3)))
  }

  def batchUserURIsInterests(implicit versionOpt: Option[Int]) = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userId = (js \ "userId").as[Id[User]]
    val uriIds = (js \ "uriIds").as[Seq[Id[NormalizedURI]]]
    val version = getVersionForUser(versionOpt.map { ModelVersion[DenseLDA](_) }, Some(userId))
    val scores = lda.batchUserURIsInterests(userId, uriIds)(version)
    Ok(Json.toJson(scores))
  }

  def userTopicMean(userId: Id[User], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val feat = lda.userTopicMean(userId)
    val meanOpt = feat.flatMap { _.userTopicMean }
    val recentOpt = feat.flatMap { _.userRecentTopicMean }
    Ok(Json.obj("global" -> meanOpt.map { _.mean }, "recent" -> recentOpt.map { _.mean }))
  }

  def libraryTopic(libId: Id[Library], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val feat = lda.libraryTopic(libId).flatMap { _.topic }
    val vecOpt = feat.map(_.value)
    Ok(Json.toJson(vecOpt))
  }

  def userLibraryScore(userId: Id[User], libId: Id[Library], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val s = lda.userLibraryScore(userId, libId)
    Ok(Json.toJson(s))
  }

  def userLibrariesScores(userId: Id[User], version: Option[Int]) = Action(parse.tolerantJson) { request =>
    val libIds = request.body.as[Seq[Id[Library]]]
    implicit val ver = toVersion(version)
    val s = lda.userLibrariesScores(userId, libIds)
    Ok(Json.toJson(s))
  }

  def sampleURIs(topicId: Int, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val (uris, scores) = lda.sampleURIs(topicId).unzip
    Ok(Json.obj("uris" -> uris, "scores" -> scores))
  }

  def getSimilarUsers(userId: Id[User], topK: Int, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val (ids, scores) = lda.getSimilarUsers(userId, topK)
    Ok(Json.obj("userIds" -> ids, "scores" -> scores))
  }

  def userSimilarity(userId1: Id[User], userId2: Id[User], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val score = lda.userSimilairty(userId1, userId2)
    Ok(Json.toJson(score))
  }

  def unamedTopics(limit: Int, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val (infos, words) = infoCommander.unamedTopics(limit)
    Ok(Json.obj("infos" -> infos, "words" -> words))
  }

  def getTopicNames(implicit versionOpt: Option[Int]) = Action(parse.tolerantJson) { request =>
    val js = request.body
    val uriIds = (js \ "uris").as[Seq[Id[NormalizedURI]]]
    val userId = (js \ "user").asOpt[Id[User]]
    val version = getVersionForUser(versionOpt.map { ModelVersion[DenseLDA](_) }, userId)
    val res = lda.getTopicNames(uriIds)(version)
    Ok(Json.toJson(res))
  }

  def explainFeed(implicit versionOpt: Option[Int]) = Action(parse.tolerantJson) { request =>
    val js = request.body
    val userId = (js \ "user").as[Id[User]]
    val uris = (js \ "uris").as[Seq[Id[NormalizedURI]]]
    val experiment = (js \ "experiment").asOpt[Boolean].getOrElse(false)
    val version = getVersionForUser(versionOpt.map { ModelVersion[DenseLDA](_) }, Some(userId))
    val explain = lda.explainFeed(userId, uris, experiment)(version)
    Ok(Json.toJson(explain))
  }

  def uriKLDivergence(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    Ok(Json.toJson(lda.uriKLDivergence(uri1, uri2)))
  }

  def recomputeUserLDAStat(implicit version: Option[Int]) = Action { request =>
    lda.recomputeUserLDAStats
    Ok
  }

  def libraryInducedUserUriInterest(userId: Id[User], uriId: Id[NormalizedURI], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val score = lda.libraryInducedUserURIInterest(userId, uriId)
    Ok(Json.toJson(score))
  }

  def dumpFeature(dataType: String, id: Long, version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    Ok(lda.dumpFeature(dataType, id))
  }

  def getSimilarURIs(uriId: Id[NormalizedURI], version: Option[Int]) = Action { request =>
    implicit val ver = toVersion(version)
    val uris = lda.getSimilarURIs(uriId)
    Ok(Json.toJson(uris))
  }

  def getSimilarLibraries(libId: Id[Library], limit: Int, version: Option[Int]) = Action { request =>
    val ver = ModelVersion[DenseLDA](3) // just use this for now.
    val libs = statsd.time("ldaController.getSimilarLibraries", 1.0) { _ => lda.getSimilarLibraries(libId, limit)(ver) }
    Ok(Json.toJson(libs))
  }

}
