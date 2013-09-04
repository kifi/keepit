package com.keepit.common.service

import scala.concurrent.Future

import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, ErrorMessage, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ClientResponse, HttpClient}
import com.keepit.common.routes._
import com.keepit.common.zookeeper.ServiceCluster

import play.api.libs.json.{JsNull, JsValue}
import play.api.libs.concurrent.Execution.Implicits._

class ServiceNotAvailableException(serviceType: ServiceType)
  extends Exception(s"Service of type ${serviceType.name} is not available")

object ServiceClient {
  val MaxUrlLength = 1000
}

trait ServiceClient extends Logging {
  protected def httpClient: HttpClient

  val serviceCluster: ServiceCluster
  val healthcheck: HealthcheckPlugin

  private def nextHost(): String = serviceCluster.nextService map { service =>
    service.instanceInfo.localHostname
  } getOrElse (throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${nextHost()}:${port}${path}"

  protected def urls(path: String): Seq[String] = {
    val fullUrls = serviceCluster.myNode match {
      case Some(node) =>
        serviceCluster.allServices.filterNot(_.node == node).map{service => s"${protocol}://${service.instanceInfo.localHostname}:${port}${path}" }
      case None =>
        serviceCluster.allServices map {service => s"${protocol}://${service.instanceInfo.localHostname}:${port}${path}" }
    }
    if (fullUrls.length==0) log.warn("Broadcasting to no-one!")
    fullUrls
  }

  protected def call(call: ServiceRoute, body: JsValue = JsNull, attempts : Int = 2): Future[ClientResponse] = {
    var respFuture = callUrl(call, url(call.url), body)
    (1 until attempts).foreach { _ =>
        respFuture = respFuture.recoverWith {
          case _ : java.net.ConnectException => callUrl(call, url(call.url), body)
        }
    }
    respFuture
  }

  protected def callUrl(call: ServiceRoute, url: String, body: JsValue): Future[ClientResponse] = {
    if (url.length > ServiceClient.MaxUrlLength) {
      healthcheck.addError(HealthcheckError(callType = Healthcheck.INTERNAL, errorMessage = Some(ErrorMessage(
        "Request URI too long!", Some(s"Request URI length ${url.length} > ${ServiceClient.MaxUrlLength}: $url"
      )))))
    }
    call match {
      case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(url)
      case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(url, body)
    }
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ClientResponse]] =
    urls(call.url) map { url =>
      log.info(s"[broadcast] Sending to $url: ${body.toString.take(120)}")
      callUrl(call, url, body)
    }
}
