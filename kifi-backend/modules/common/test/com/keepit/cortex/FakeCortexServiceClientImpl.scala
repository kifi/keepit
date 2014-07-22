package com.keepit.cortex

import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI, Word2VecKeywords }
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ UriSparseLDAFeatures, DenseLDA }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.cortex.models.lda.LDATopicConfiguration
import com.keepit.cortex.models.lda.LDATopicInfo

class FakeCortexServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CortexServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  override def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]] = ???
  override def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]] = ???
  override def word2vecURIKeywords(uri: Id[NormalizedURI]): Future[Option[Word2VecKeywords]] = ???
  override def word2vecBatchURIKeywords(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]] = ???
  override def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]] = ???
  override def word2vecUserSimilarity(user1Keeps: Seq[Id[NormalizedURI]], user2Keeps: Seq[Id[NormalizedURI]]): Future[Option[Float]] = ???
  override def word2vecQueryUriSimilarity(query: String, uri: Id[NormalizedURI]): Future[Option[Float]] = ???
  override def word2vecUserUriSimilarity(userUris: Seq[Id[NormalizedURI]], uri: Id[NormalizedURI]): Future[Map[String, Float]] = ???
  override def word2vecFeedUserUris(userUris: Seq[Id[NormalizedURI]], feedUris: Seq[Id[NormalizedURI]]): Future[Seq[Id[NormalizedURI]]] = ???

  override def ldaNumOfTopics(): Future[Int] = ???
  override def ldaShowTopics(fromId: Int, toId: Int, topN: Int): Future[Seq[LDATopicInfo]] = ???
  override def ldaWordTopic(word: String): Future[Option[Array[Float]]] = ???
  override def ldaDocTopic(doc: String): Future[Option[Array[Float]]] = ???
  override def saveEdits(configs: Map[String, LDATopicConfiguration]): Unit = ???
  override def getLDAFeatures(uris: Seq[Id[NormalizedURI]]): Future[Seq[Array[Float]]] = ???
  override def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): Future[Option[Float]] = ???
  override def userTopicMean(userId: Id[User]): Future[Option[Array[Float]]] = ???
  override def sampleURIsForTopic(topic: Int): Future[Seq[Id[NormalizedURI]]] = ???

  override def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = ???
}
