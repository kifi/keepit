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

  override def defaultLDAVersion(): Future[ModelVersion[DenseLDA]] = ???
  override def ldaNumOfTopics(implicit version: LDAVersionOpt = None): Future[Int] = ???
  override def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt = None): Future[Seq[LDATopicInfo]] = ???
  override def ldaConfigurations(implicit version: LDAVersionOpt): Future[LDATopicConfigurations] = Future.successful(LDATopicConfigurations(Map()))
  override def ldaWordTopic(word: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???
  override def ldaDocTopic(doc: String)(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???
  override def saveEdits(configs: Map[String, LDATopicConfiguration])(implicit version: LDAVersionOpt = None): Unit = ???
  override def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt = None): Future[LDAUserURIInterestScores] = Future.successful(LDAUserURIInterestScores(None, None, None))
  override def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt = None): Future[(Option[Array[Float]], Option[Array[Float]])] = ???
  override def sampleURIsForTopic(topic: Int)(implicit version: LDAVersionOpt): Future[(Seq[Id[NormalizedURI]], Seq[Float])] = ???
  override def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt = None): Future[(Seq[Id[User]], Seq[Float])] = ???
  override def unamedTopics(limit: Int = 20)(implicit version: LDAVersionOpt = None): Future[(Seq[LDAInfo], Seq[Map[String, Float]])] = ???
  override def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt = None): Future[Option[Array[Float]]] = ???

  override def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt): Future[Option[Float]] = {
    Future.successful {
      userLibraryScoreExpectations.getOrElse((userId, libId), Some(1f))
    }
  }
  override def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt): Future[Seq[Id[Library]]] = Future.successful(Seq.empty)
  override def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[(ModelVersion[DenseLDA], Seq[UriSparseLDAFeatures])] = ???
}
