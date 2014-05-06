package com.keepit.graph

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.common.routes.Graph
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{GraphUpdaterState, PrettyGraphStatistics}

trait GraphServiceClient extends ServiceClient {
  final val serviceType = ServiceType.GRAPH

  def getGraphStatistics(): Future[PrettyGraphStatistics]
  def getGraphUpdaterStates(): Future[GraphUpdaterState]
}

class GraphServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier
) extends GraphServiceClient {

  def getGraphStatistics(): Future[PrettyGraphStatistics] = {
    call(Graph.internal.getGraphStatistics()).map { response =>
      response.json.as[PrettyGraphStatistics]
    }
  }

  def getGraphUpdaterStates(): Future[GraphUpdaterState] = {
    call(Graph.internal.getGraphUpdaterState()).map { response =>
      response.json.as[GraphUpdaterState]
    }
  }
}