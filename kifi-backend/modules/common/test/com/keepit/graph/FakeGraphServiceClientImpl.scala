package com.keepit.graph

import com.keepit.common.db.Id
import com.keepit.model.User

import scala.concurrent.Future
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.wander.{ Collisions, Wanderlust }
import com.keepit.graph.model.{ UserConnectionSocialScore, UserConnectionFeedScore, GraphKinds }

class FakeGraphServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends GraphServiceClient {
  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]] = Future.successful(Map.empty)
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]] = Future.successful(Map.empty)
  def getGraphKinds(): Future[GraphKinds] = Future.successful(GraphKinds.empty)
  def wander(wanderlust: Wanderlust): Future[Collisions] = Future.successful(Collisions.empty)
  def getListOfUriAndScorePairs(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[UserConnectionFeedScore]] = Future.successful(Seq.empty)
  def getListOfUserAndScorePairs(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[UserConnectionSocialScore]] = Future.successful(Seq.empty)
}
