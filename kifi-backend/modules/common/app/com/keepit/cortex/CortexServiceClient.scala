package com.keepit.cortex

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.routes.Cortex
import com.keepit.cortex.dbmodel.LDAInfo
import com.keepit.cortex.models.word2vec.Word2VecKeywords
import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.common.net.CallTimeouts
import com.keepit.cortex.models.lda._

trait CortexServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CORTEX

  type LDAVersion = ModelVersion[DenseLDA]
  type LDAVersionOpt = Option[LDAVersion]

  def defaultLDAVersion(): Future[ModelVersion[DenseLDA]]
  def ldaNumOfTopics(implicit version: LDAVersionOpt = None): Future[Int]
  def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt = None): Future[Seq[LDATopicInfo]]
  def ldaConfigurations(implicit version: LDAVersionOpt): Future[LDATopicConfigurations]
  def ldaWordTopic(word: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]]
  def ldaDocTopic(doc: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]]
  def saveEdits(configs: Map[String, LDATopicConfiguration])(implicit version: LDAVersionOpt): Unit
  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt = None): Future[LDAUserURIInterestScores]
  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[LDAUserURIInterestScores]]
  def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt = None): Future[(Option[Array[Float]], Option[Array[Float]])]
  def sampleURIsForTopic(topic: Int)(implicit version: LDAVersionOpt): Future[(Seq[Id[NormalizedURI]], Seq[Float])]
  def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt = None): Future[(Seq[Id[User]], Seq[Float])] // with scores
  def unamedTopics(limit: Int = 20)(implicit version: LDAVersionOpt = None): Future[(Seq[LDAInfo], Seq[Map[String, Float]])] // with topicWords
  def getTopicNames(uris: Seq[Id[NormalizedURI]], userIdOpt: Option[Id[User]])(implicit version: LDAVersionOpt = None): Future[Seq[Option[String]]]
  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[Seq[Id[Keep]]]]
  def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]]
  def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt): Future[Option[Float]]
  def userLibrariesScores(userId: Id[User], libIds: Seq[Id[Library]])(implicit version: LDAVersionOpt): Future[Seq[Option[Float]]]
  def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt = None): Future[Seq[Id[Library]]]

  def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])]
}

class CortexServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CortexServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  def defaultLDAVersion(): Future[ModelVersion[DenseLDA]] = {
    call(Cortex.internal.defulatLDAVersion()).map { r => (r.json).as[ModelVersion[DenseLDA]] }
  }

  def ldaNumOfTopics(implicit version: LDAVersionOpt = None): Future[Int] = {
    call(Cortex.internal.ldaNumOfTopics).map { r =>
      (r.json).as[Int]
    }
  }

  def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt = None): Future[Seq[LDATopicInfo]] = {
    call(Cortex.internal.ldaShowTopics(fromId, toId, topN)).map { r =>
      (r.json).as[Seq[LDATopicInfo]]
    }
  }

  def ldaConfigurations(implicit version: LDAVersionOpt): Future[LDATopicConfigurations] = {
    call(Cortex.internal.ldaConfigurations).map { r => (r.json).as[LDATopicConfigurations] }
  }

  def ldaWordTopic(word: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = {
    call(Cortex.internal.ldaWordTopic(word)).map { r =>
      Json.fromJson[Option[Array[Float]]](r.json).get
    }
  }

  def ldaDocTopic(doc: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = {
    val payload = Json.obj("doc" -> doc)
    call(Cortex.internal.ldaDocTopic, payload).map { r =>
      Json.fromJson[Option[Array[Float]]](r.json).get
    }
  }

  def saveEdits(configs: Map[String, LDATopicConfiguration])(implicit version: LDAVersionOpt): Unit = {
    val payload = Json.toJson(configs)
    call(Cortex.internal.saveEdits(version), payload)
  }

  def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = {
    call(Cortex.internal.getSparseLDAFeaturesChanged(modelVersion, seqNum, fetchSize), callTimeouts = longTimeout).map { response =>
      val publishedModelVersion = (response.json \ "modelVersion").as[ModelVersion[DenseLDA]]
      val uriFeatures = (response.json \ "features").as[JsArray].value.map(_.as[UriSparseLDAFeatures])
      (publishedModelVersion, uriFeatures)
    }
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt = None): Future[LDAUserURIInterestScores] = {
    call(Cortex.internal.userUriInterest(userId, uriId)).map { r => r.json.as[LDAUserURIInterestScores] }
  }

  def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[LDAUserURIInterestScores]] = {
    val payload = Json.obj("userId" -> userId, "uriIds" -> Json.toJson(uriIds))
    call(Cortex.internal.batchUserURIsInterests, payload, callTimeouts = longTimeout).map { r => (r.json).as[Seq[LDAUserURIInterestScores]] }
  }

  def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt = None): Future[(Option[Array[Float]], Option[Array[Float]])] = {
    call(Cortex.internal.userTopicMean(userId)).map { r =>
      val js = r.json
      val globalOpt = (js \ "global").asOpt[JsArray]
      val global = globalOpt.map { arr => arr.value.map { x => x.as[Float] }.toArray }
      val recentOpt = (js \ "recent").asOpt[JsArray]
      val recent = recentOpt.map { arr => arr.value.map { x => x.as[Float] }.toArray }
      (global, recent)
    }
  }

  def sampleURIsForTopic(topic: Int)(implicit version: LDAVersionOpt): Future[(Seq[Id[NormalizedURI]], Seq[Float])] = {
    call(Cortex.internal.sampleURIsForTopic(topic)).map { r =>
      val js = r.json
      val uris = (js \ "uris").as[Seq[Id[NormalizedURI]]]
      val scores = (js \ "scores").as[Seq[Float]]
      (uris, scores)
    }
  }

  def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt = None): Future[(Seq[Id[User]], Seq[Float])] = {
    call(Cortex.internal.getSimilarUsers(userId, topK)).map { r =>
      val js = r.json
      val users = (js \ "userIds").as[Seq[Id[User]]]
      val scores = (js \ "scores").as[Seq[Float]]
      (users, scores)
    }
  }

  def unamedTopics(limit: Int = 20)(implicit version: LDAVersionOpt = None): Future[(Seq[LDAInfo], Seq[Map[String, Float]])] = {
    call(Cortex.internal.unamedTopics(limit)).map { r =>
      val js = r.json
      val infos = (js \ "infos").as[Seq[LDAInfo]]
      val words = (js \ "words").as[Seq[Map[String, Float]]]
      (infos, words)
    }
  }

  def getTopicNames(uris: Seq[Id[NormalizedURI]], userIdOpt: Option[Id[User]])(implicit version: LDAVersionOpt = None): Future[Seq[Option[String]]] = {
    val payload = Json.obj("uris" -> uris, "user" -> userIdOpt)
    call(Cortex.internal.getTopicNames, payload).map { r => (r.json).as[Seq[Option[String]]] }
  }

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[Seq[Id[Keep]]]] = {
    val payload = Json.obj("user" -> userId, "uris" -> uriIds)
    call(Cortex.internal.explainFeed, payload).map { r => (r.json).as[Seq[Seq[Id[Keep]]]] }
  }

  def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = {
    call(Cortex.internal.libraryTopic(libId)).map { r => (r.json).as[Option[Array[Float]]] }
  }

  // TODO(yingie / josh) change response object to extendable case class
  def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt): Future[Option[Float]] = {
    call(Cortex.internal.userLibraryScore(userId, libId)).map { r => (r.json).as[Option[Float]] }
  }

  def userLibrariesScores(userId: Id[User], libIds: Seq[Id[Library]])(implicit version: LDAVersionOpt): Future[Seq[Option[Float]]] = {
    val payload = Json.toJson(libIds)
    call(Cortex.internal.userLibrariesScores(userId), payload).map { r => r.json.as[Seq[Option[Float]]] }
  }

  def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt = None): Future[Seq[Id[Library]]] = {
    call(Cortex.internal.similarLibraries(libId, limit)).map { r => r.json.as[Seq[Id[Library]]] }
  }
}
