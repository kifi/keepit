package com.keepit.graph

import scala.concurrent.Future
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{PrettyGraphState, PrettyGraphStatistics}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier

class FakeGraphServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier
) extends GraphServiceClient {
  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]] = Future.successful(Map.empty)
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]] = Future.successful(Map.empty)
}
