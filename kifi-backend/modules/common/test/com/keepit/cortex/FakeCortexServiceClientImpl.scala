package com.keepit.cortex

import com.keepit.cortex.dbmodel.LDAInfo

import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.model.{ Keep, User, NormalizedURI, Word2VecKeywords }
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler

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
  override def ldaConfigurations: Future[LDATopicConfigurations] = Future.successful(LDATopicConfigurations(Map()))
  override def ldaWordTopic(word: String): Future[Option[Array[Float]]] = ???
  override def ldaDocTopic(doc: String): Future[Option[Array[Float]]] = ???
  override def saveEdits(configs: Map[String, LDATopicConfiguration]): Unit = ???
  override def getLDAFeatures(uris: Seq[Id[NormalizedURI]]): Future[Seq[Array[Float]]] = ???
  override def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI]): Future[LDAUserURIInterestScores] = Future.successful(LDAUserURIInterestScores(None, None))
  override def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[LDAUserURIInterestScores]] = {
    Future.successful((0 until uriIds.length).map(_ => LDAUserURIInterestScores(None, None)))
  }
  override def userTopicMean(userId: Id[User]): Future[(Option[Array[Float]], Option[Array[Float]])] = ???
  override def sampleURIsForTopic(topic: Int): Future[Seq[Id[NormalizedURI]]] = ???
  override def getSimilarUsers(userId: Id[User], topK: Int): Future[(Seq[Id[User]], Seq[Float])] = ???
  override def unamedTopics(limit: Int = 20): Future[(Seq[LDAInfo], Seq[Map[String, Float]])] = ???
  override def getTopicNames(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[String]]] = Future.successful(Seq.fill(uris.length)(None))
  override def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Seq[Id[Keep]]]] = Future.successful(Seq.fill(uriIds.length)(Seq()))

  override def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = ???
}
