package com.keepit.graph

import com.keepit.common.db.{ Id }
import com.keepit.model._

import scala.collection.mutable
import scala.concurrent.Future
import com.keepit.common.amazon.{ AmazonInstanceInfo, AmazonInstanceId }
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.wander.{ Collisions, Wanderlust }
import com.keepit.graph.model._
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.abook.model.EmailAccountInfo

class FakeGraphServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends GraphServiceClient {
  var uriAndScores: Map[Id[NormalizedURI], Int] = Map.empty
  val connectedUserScoresExpectations = collection.mutable.Map[Id[User], Seq[ConnectedUserScore]]()

  def getGraphStatistics(): Future[Map[AmazonInstanceInfo, PrettyGraphStatistics]] = Future.successful(Map.empty)
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceInfo, PrettyGraphState]] = Future.successful(Map.empty)
  def getGraphKinds(): Future[GraphKinds] = Future.successful(GraphKinds.empty)
  def wander(wanderlust: Wanderlust): Future[Collisions] = Future.successful(Collisions.empty)
  def uriWander(userId: Id[User], steps: Int): Future[Map[Id[NormalizedURI], Int]] = Future.successful(uriAndScores)
  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[ConnectedUriScore]] = Future.successful(Seq.empty)
  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[ConnectedUserScore]] =
    Future.successful(connectedUserScoresExpectations.getOrElse(userId, Seq.empty))

  def setUriAndScorePairs(uris: Seq[Id[NormalizedURI]]) {
    uris.foreach(uri =>
      uriAndScores = uriAndScores + (uri -> 42)
    )
  }

  def setUserAndScorePairs(userId: Id[User]): Seq[ConnectedUserScore] = {
    val connectedUserScore1 = ConnectedUserScore(Id[User](1), 0.795)
    val connectedUserScore2 = ConnectedUserScore(Id[User](2), 0.795)
    val connectedUserScore3 = ConnectedUserScore(Id[User](3), 0.795)
    val scores = Seq(connectedUserScore1, connectedUserScore2, connectedUserScore3)
    connectedUserScoresExpectations(userId) = scores
    scores
  }

  val sociallyRelatedEntitiesMap = mutable.Map[Id[User], SociallyRelatedEntities]()

  def setSociallyRelatedEntities(userId: Id[User], sociallyRelatedEntities: SociallyRelatedEntities): Unit = {
    sociallyRelatedEntitiesMap(userId) = sociallyRelatedEntities
  }

  def getSociallyRelatedEntities(userId: Id[User]): Future[Option[SociallyRelatedEntities]] = {
    val rels = sociallyRelatedEntitiesMap.get(userId)
    Future.successful(rels)
  }

  def refreshSociallyRelatedEntities(userId: Id[User]): Future[Unit] = Future.successful(())

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = Future.successful(Seq.fill(uriIds.size)(GraphFeedExplanation(Map(), Map())))
}
