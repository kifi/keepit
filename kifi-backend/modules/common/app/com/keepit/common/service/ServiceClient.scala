package com.keepit.common.service

import scala.concurrent.Future

import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{ClientResponse, HttpClient}
import com.keepit.common.routes._

import play.api.libs.json.{JsNull, JsValue}
import play.api.mvc.Call

class ServiceNotAvailableException(serviceType: ServiceType)
  extends Exception(s"Service of type ${serviceType.name} is not available")

trait ServiceClient extends Logging {
  protected def httpClient: HttpClient

  val serviceCluster: ServiceCluster

  private def nextHost(): String = serviceCluster.nextService map { service =>
    service.instanceInfo.localHostname
  } getOrElse (throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${nextHost()}:${port}${path}"

  protected def urls(path: String): Seq[String] = serviceCluster.myNode match {
    case Some(node) =>
      serviceCluster.allServices.filterNot(_.node == node).map{service => s"${protocol}://${service.instanceInfo.localHostname}:${port}${path}" }
    case None =>
      serviceCluster.allServices map {service => s"${protocol}://${service.instanceInfo.localHostname}:${port}${path}" }
  }

  protected def call(call: ServiceRoute, body: JsValue = JsNull): Future[ClientResponse] = callUrl(call, url(call.url), body)

  protected def callUrl(call: ServiceRoute, url: String, body: JsValue): Future[ClientResponse] = call match {
    case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(url)
    case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(url, body)
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ClientResponse]] =
    urls(call.url) map { url => callUrl(call, url, body) }
}
