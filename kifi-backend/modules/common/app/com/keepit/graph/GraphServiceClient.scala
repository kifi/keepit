package com.keepit.graph

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{CallTimeouts, ClientResponse, HttpClient}
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.common.routes.Graph
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{PrettyGraphState, PrettyGraphStatistics}
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.graph.wander.{Wanderlust, Collisions}
import play.api.libs.json.Json
import com.keepit.graph.model.GraphKinds

trait GraphServiceClient extends ServiceClient {
  final val serviceType = ServiceType.GRAPH

  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]]
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]]
  def getGraphKinds(): Future[GraphKinds]
  def wander(wanderlust: Wanderlust): Future[Collisions]
}

class GraphServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  mode: Mode
) extends GraphServiceClient {

  private val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  private def getSuccessfulResponses(calls: Seq[Future[ClientResponse]]): Future[Seq[ClientResponse]] = {
    val safeCalls = calls.map { call =>
      call.map(Some(_)).recover { case error: Throwable =>
        log.error("Failed to complete service call:", error)
        None
      }
    }
    Future.sequence(safeCalls).map(_.flatten)
  }

  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]] = {
    getSuccessfulResponses(broadcast(Graph.internal.getGraphStatistics(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
      responses.map { response =>
        response.request.instance.get.instanceInfo.instanceId -> response.json.as[PrettyGraphStatistics]
      }.toMap
    }
  }

  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]] = {
    getSuccessfulResponses(broadcast(Graph.internal.getGraphUpdaterState(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
      responses.map { response =>
        response.request.instance.get.instanceInfo.instanceId -> response.json.as[PrettyGraphState]
      }.toMap
    }
  }

  def getGraphKinds(): Future[GraphKinds] = {
    call(Graph.internal.getGraphKinds()).map { response => response.json.as[GraphKinds]}
  }

  def wander(wanderlust: Wanderlust): Future[Collisions] = {
    val payload = Json.toJson(wanderlust)
    call(Graph.internal.wander(), payload, callTimeouts = longTimeout).map { response => response.json.as[Collisions] }
  }
}