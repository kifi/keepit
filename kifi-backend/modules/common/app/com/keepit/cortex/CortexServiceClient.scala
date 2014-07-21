package com.keepit.cortex

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.routes.Cortex
import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI, Word2VecKeywords }
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ UriSparseLDAFeatures, DenseLDA }
import com.keepit.serializer.TraversableFormat
import com.keepit.common.net.CallTimeouts
import com.keepit.cortex.models.lda._

trait CortexServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CORTEX

  def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]]
  def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]]
  def word2vecURIKeywords(uri: Id[NormalizedURI]): Future[Option[Word2VecKeywords]]
  def word2vecBatchURIKeywords(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]]
  def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]]
  def word2vecUserSimilarity(user1Keeps: Seq[Id[NormalizedURI]], user2Keeps: Seq[Id[NormalizedURI]]): Future[Option[Float]]
  def word2vecQueryUriSimilarity(query: String, uri: Id[NormalizedURI]): Future[Option[Float]]
  def word2vecUserUriSimilarity(userUris: Seq[Id[NormalizedURI]], uri: Id[NormalizedURI]): Future[Map[String, Float]]
  def word2vecFeedUserUris(userUris: Seq[Id[NormalizedURI]], feedUris: Seq[Id[NormalizedURI]]): Future[Seq[Id[NormalizedURI]]]

  def ldaNumOfTopics(): Future[Int]
  def ldaShowTopics(fromId: Int, toId: Int, topN: Int): Future[Seq[LDATopicInfo]]
  def ldaWordTopic(word: String): Future[Option[Array[Float]]]
  def ldaDocTopic(doc: String): Future[Option[Array[Float]]]
  def saveEdits(configs: Map[String, LDATopicConfiguration]): Unit
  def getLDAFeatures(uris: Seq[Id[NormalizedURI]]): Future[Seq[Array[Float]]]
  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): Future[Option[Float]]
  def userTopicMean(userId: Id[User]): Future[Option[Array[Float]]]

  def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])]
}

class CortexServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CortexServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]] = {
    call(Cortex.internal.word2vecSimilairty(word1, word2)).map { r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }

  def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]] = {
    val payload = Json.obj("query" -> text)
    call(Cortex.internal.keywordsAndBow(), payload).map { r =>
      Json.fromJson[Map[String, String]](r.json).get
    }
  }

  def word2vecURIKeywords(uri: Id[NormalizedURI]): Future[Option[Word2VecKeywords]] = {
    call(Cortex.internal.uriKeywords(uri)).map { r =>
      r.json.as[Option[Word2VecKeywords]]
    }
  }

  def word2vecBatchURIKeywords(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]] = {
    val payload = Json.toJson(uris)
    call(Cortex.internal.batchGetURIKeywords(), payload) map { r => r.json.as[Seq[Option[Word2VecKeywords]]] }
  }

  def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]] = {
    call(Cortex.internal.word2vecURISimilarity(uri1, uri2)).map { r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }

  def word2vecUserSimilarity(user1Keeps: Seq[Id[NormalizedURI]], user2Keeps: Seq[Id[NormalizedURI]]): Future[Option[Float]] = {
    val payload = Json.obj("uris1" -> user1Keeps.map { _.id }, "uris2" -> user2Keeps.map { _.id })
    call(Cortex.internal.word2vecUserSimilarity(), payload).map { r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }

  def word2vecQueryUriSimilarity(query: String, uri: Id[NormalizedURI]): Future[Option[Float]] = {
    val payload = Json.obj("query" -> query, "uri" -> uri.id)
    call(Cortex.internal.word2vecQueryUriSimilarity(), payload).map { r =>
      Json.fromJson[Option[Float]](r.json).get
    }
  }

  def word2vecUserUriSimilarity(userUris: Seq[Id[NormalizedURI]], uri: Id[NormalizedURI]): Future[Map[String, Float]] = {
    val payload = Json.obj("userUris" -> userUris.map { _.id }, "uri" -> uri.id)
    call(Cortex.internal.word2vecUserUriSimilarity(), payload).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def word2vecFeedUserUris(userUris: Seq[Id[NormalizedURI]], feedUris: Seq[Id[NormalizedURI]]): Future[Seq[Id[NormalizedURI]]] = {
    val payload = Json.obj("userUris" -> userUris.map { _.id }, "feedUris" -> feedUris.map { _.id })
    call(Cortex.internal.word2vecFeedUserUris(), payload).map { r =>
      Json.fromJson[Seq[Id[NormalizedURI]]](r.json).get
    }
  }

  def ldaNumOfTopics(): Future[Int] = {
    call(Cortex.internal.ldaNumOfTopics).map { r =>
      (r.json).as[Int]
    }
  }

  def ldaShowTopics(fromId: Int, toId: Int, topN: Int): Future[Seq[LDATopicInfo]] = {
    call(Cortex.internal.ldaShowTopics(fromId, toId, topN)).map { r =>
      (r.json).as[Seq[LDATopicInfo]]
    }
  }

  def ldaWordTopic(word: String): Future[Option[Array[Float]]] = {
    call(Cortex.internal.ldaWordTopic(word)).map { r =>
      Json.fromJson[Option[Array[Float]]](r.json).get
    }
  }

  def ldaDocTopic(doc: String): Future[Option[Array[Float]]] = {
    val payload = Json.obj("doc" -> doc)
    call(Cortex.internal.ldaDocTopic(), payload).map { r =>
      Json.fromJson[Option[Array[Float]]](r.json).get
    }
  }

  def saveEdits(configs: Map[String, LDATopicConfiguration]): Unit = {
    val payload = Json.toJson(configs)
    broadcast(Cortex.internal.saveEdits(), payload)
  }

  def getLDAFeatures(uris: Seq[Id[NormalizedURI]]): Future[Seq[Array[Float]]] = {
    val payload = Json.toJson(uris)
    call(Cortex.internal.getLDAFeatures, payload).map { r =>
      (r.json).as[Seq[Array[Float]]]
    }
  }

  def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = {
    call(Cortex.internal.getSparseLDAFeaturesChanged(modelVersion, seqNum, fetchSize), callTimeouts = longTimeout).map { response =>
      val publishedModelVersion = (response.json \ "modelVersion").as[ModelVersion[DenseLDA]]
      val uriFeatures = (response.json \ "features").as[JsArray].value.map(_.as[UriSparseLDAFeatures])
      (publishedModelVersion, uriFeatures)
    }
  }

  def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): Future[Option[Float]] = {
    call(Cortex.internal.userUriInterest(userId, uriId)).map { r => (r.json).asOpt[Float] }
  }

  def userTopicMean(userId: Id[User]): Future[Option[Array[Float]]] = {
    call(Cortex.internal.userTopicMean(userId)).map { r =>
      val jsArrOpt = (r.json).asOpt[JsArray]
      jsArrOpt.map { arr => arr.value.map { x => x.as[Float] }.toArray }
    }
  }

}
