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

  val sociallyRelatedEntitiesForUserMap = mutable.Map[Id[User], SociallyRelatedEntitiesForUser]()
  val sociallyRelatedEntitiesForOrgMap = mutable.Map[Id[Organization], SociallyRelatedEntitiesForOrg]()

  def setSociallyRelatedEntitiesForUser(userId: Id[User], sociallyRelatedEntities: SociallyRelatedEntitiesForUser): Unit = {
    sociallyRelatedEntitiesForUserMap(userId) = sociallyRelatedEntities
  }

  def setSociallyRelatedEntitiesForOrg(orgId: Id[Organization], sociallyRelatedEntities: SociallyRelatedEntitiesForOrg): Unit = {
    sociallyRelatedEntitiesForOrgMap(orgId) = sociallyRelatedEntities
  }

  def getSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Option[SociallyRelatedEntitiesForUser]] = {
    val rels = sociallyRelatedEntitiesForUserMap.get(userId)
    Future.successful(rels)
  }

  def getSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]] = {
    val relatedEntities = sociallyRelatedEntitiesForOrgMap.get(orgId)
    Future.successful(relatedEntities)
  }

  def refreshSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Unit] = Future.successful(())

  def refreshSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Unit] = Future.successful(())

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = Future.successful(Seq.fill(uriIds.size)(GraphFeedExplanation(Map(), Map())))
}
