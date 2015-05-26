package com.keepit.cortex

import com.keepit.cortex.dbmodel.LDAInfo
import com.keepit.cortex.models.word2vec.Word2VecKeywords

import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler

class FakeCortexServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CortexServiceClient {
  val batchUserURIsInterestsExpectations = collection.mutable.Map[Id[User], Seq[LDAUserURIInterestScores]]()
  val userLibraryScoreExpectations = collection.mutable.Map[(Id[User], Id[Library]), Option[Float]]()

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

  override def defaultLDAVersion(): Future[ModelVersion[DenseLDA]] = ???
  override def ldaNumOfTopics(implicit version: LDAVersionOpt = None): Future[Int] = ???
  override def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt = None): Future[Seq[LDATopicInfo]] = ???
  override def ldaConfigurations(implicit version: LDAVersionOpt): Future[LDATopicConfigurations] = Future.successful(LDATopicConfigurations(Map()))
  override def ldaWordTopic(word: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???
  override def ldaDocTopic(doc: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???
  override def saveEdits(configs: Map[String, LDATopicConfiguration])(implicit version: LDAVersionOpt = None): Unit = ???
  override def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt = None): Future[LDAUserURIInterestScores] = Future.successful(LDAUserURIInterestScores(None, None, None))
  override def batchUserURIsInterests(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[LDAUserURIInterestScores]] = {
    val result = batchUserURIsInterestsExpectations.getOrElse(userId,
      (0 until uriIds.length).map(_ => LDAUserURIInterestScores(None, None, None)))
    Future.successful(result)
  }
  override def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt = None): Future[(Option[Array[Float]], Option[Array[Float]])] = ???
  override def sampleURIsForTopic(topic: Int)(implicit version: LDAVersionOpt): Future[(Seq[Id[NormalizedURI]], Seq[Float])] = ???
  override def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt = None): Future[(Seq[Id[User]], Seq[Float])] = ???
  override def unamedTopics(limit: Int = 20)(implicit version: LDAVersionOpt = None): Future[(Seq[LDAInfo], Seq[Map[String, Float]])] = ???
  override def getTopicNames(uris: Seq[Id[NormalizedURI]], userIdOpt: Option[Id[User]])(implicit version: LDAVersionOpt = None): Future[Seq[Option[String]]] = Future.successful(Seq.fill(uris.length)(None))
  override def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit version: LDAVersionOpt = None): Future[Seq[Seq[Id[Keep]]]] = Future.successful(Seq.fill(uriIds.length)(Seq()))
  override def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???

  override def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt): Future[Option[Float]] = {
    Future.successful {
      userLibraryScoreExpectations.getOrElse((userId, libId), Some(1f))
    }
  }

  override def userLibrariesScores(userId: Id[User], libIds: Seq[Id[Library]])(implicit version: LDAVersionOpt): Future[Seq[Option[Float]]] = {
    Future.successful {
      libIds.map(libId => userLibraryScoreExpectations.getOrElse((userId, libId), Some(1f)))
    }
  }

  override def similarURIs(uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt): Future[Seq[Id[NormalizedURI]]] = ???
  override def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt): Future[Seq[Id[Library]]] = Future.successful(Seq.empty)

  override def generatePersonaFeature(topicIds: Seq[LDATopic])(implicit version: LDAVersion): Future[(Array[Float], Int)] = ???
  override def getExistingPersonaFeature(personaId: Id[Persona])(implicit version: LDAVersion): Future[Option[Array[Float]]] = ???
  override def savePersonaFeature(personaId: Id[Persona], feature: Array[Float])(implicit version: LDAVersion): Unit = ???
  override def evaluatePersona(personaId: Id[Persona])(implicit version: LDAVersion): Future[Map[Id[NormalizedURI], Float]] = ???
  override def trainPersona(personaId: Id[Persona], uriIds: Seq[Id[NormalizedURI]], labels: Seq[Int], rate: Float)(implicit version: LDAVersion): Unit = ???

  override def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = ???
}
