package com.keepit.graph

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.common.routes.Graph
import play.api.libs.json.{JsNumber, JsString, JsArray}

trait GraphServiceClient extends ServiceClient {
  final val serviceType = ServiceType.GRAPH

  def getGraphStatistics(): Future[(Map[String, Long], Map[(String, String, String), Long])]
}

class GraphServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier
) extends GraphServiceClient {

  def getGraphStatistics(): Future[(Map[String, Long], Map[(String, String, String), Long])] = {
    call(Graph.internal.getGraphStatistics()).map { response =>
      val vertexStatistics = (response.json \ "vertices").validate[JsArray].map(_.value.sliding(2,2).map {
        case Seq(JsString(vertexKind), JsNumber(count)) => (vertexKind -> count.toLong)
      }.toMap)

      val edgeStatistics = (response.json \ "edges").validate[JsArray].map(_.value.sliding(4,4).map {
        case Seq(JsString(sourceKind), JsString(destinationKind), JsString(edgeKind), JsNumber(count)) => ((sourceKind, destinationKind, edgeKind) -> count.toLong)
      }.toMap)

      (vertexStatistics.get, edgeStatistics.get)
    }
  }

  def getGraphUpdaterStates(): Future[Map[String, Long]] = ???

}
