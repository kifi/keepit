package com.keepit.graph

import java.util.concurrent.atomic.AtomicInteger

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model._

import scala.concurrent.Future
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.wander.{ Collisions, Wanderlust }
import com.keepit.graph.model.{ ConnectedUserScore, ConnectedUriScore, GraphKinds }
import com.keepit.common.concurrent.ExecutionContext

class FakeGraphServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends GraphServiceClient {
  var uriAndScorePairs: Seq[ConnectedUriScore] = Seq.empty
  var userAndScorePairs: Seq[ConnectedUserScore] = Seq.empty
  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]] = Future.successful(Map.empty)
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]] = Future.successful(Map.empty)
  def getGraphKinds(): Future[GraphKinds] = Future.successful(GraphKinds.empty)
  def wander(wanderlust: Wanderlust): Future[Collisions] = Future.successful(Collisions.empty)
  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[ConnectedUriScore]] = Future.successful(uriAndScorePairs)
  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnection: Boolean): Future[Seq[ConnectedUserScore]] = Future.successful(userAndScorePairs)

  def getUriAndScorePairs(uris: Seq[Id[NormalizedURI]]) {
    uris.foreach(uri =>
      uriAndScorePairs = ConnectedUriScore(uri, 0.795) +: uriAndScorePairs
    )
  }

  def getUserAndScorePairs() {
    val connectedUserScore1 = ConnectedUserScore(Id[User](1), 0.795)
    val connectedUserScore2 = ConnectedUserScore(Id[User](2), 0.795)
    val connectedUserScore3 = ConnectedUserScore(Id[User](3), 0.795)
    userAndScorePairs = (connectedUserScore1 :: connectedUserScore2 :: connectedUserScore3 :: Nil)
  }
}
