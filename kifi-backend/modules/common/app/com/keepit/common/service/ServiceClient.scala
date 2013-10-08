package com.keepit.common.service

import scala.concurrent.Future

import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ClientResponse, HttpClient}
import com.keepit.common.routes._
import com.keepit.common.zookeeper.ServiceCluster
import java.net.ConnectException
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
  val airbrakeNotifier: AirbrakeNotifier

  private def nextHost(): String = serviceCluster.nextService map { service =>
    service.instanceInfo.localHostname
  } getOrElse (throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${nextHost()}:${port}${path}"

  protected def urls(path: String): Seq[String] = {
    val fullUrls = serviceCluster.allServices.filter(!_.thisInstance).map { service =>
      s"${protocol}://${service.instanceInfo.localHostname}:${port}${path}"
    }
    if (fullUrls.length==0) log.warn("Broadcasting to no-one!")
    fullUrls
  }

  protected def call(call: ServiceRoute, body: JsValue = JsNull, attempts : Int = 2): Future[ClientResponse] = {
    val respFuture = RetryFuture(attempts, { case t : ConnectException => true }){
      callUrl(call, url(call.url), body)
    }
    respFuture.onFailure{
      case ex: Throwable =>
        airbrakeNotifier.notify(AirbrakeError(
          exception = ex,
          message = Some(s"can't call service with body: $body and params: call.params"),
          method = Some(call.method.toString),
          url = Some(call.path)))
    }
    respFuture
  }

  protected def callUrl(call: ServiceRoute, url: String, body: JsValue, ignoreFailure: Boolean = false): Future[ClientResponse] = {
    if (url.length > ServiceClient.MaxUrlLength) {
      airbrakeNotifier.notify(AirbrakeError(
        message = Some(s"Request URI length ${url.length} > ${ServiceClient.MaxUrlLength}: $url"),
        method = Some(call.method.toString),
        url = Some(s"$url/${call.path}")))
    }
    if (ignoreFailure) {
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(url, httpClient.ignoreFailure)
        case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(url, body, httpClient.ignoreFailure)
      }
    }
    else{
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(url)
        case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(url, body)
      }
    }
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ClientResponse]] =
    urls(call.url) map { url =>
      log.info(s"[broadcast] Sending to $url: ${body.toString.take(120)}")
      callUrl(call, url, body)
    }
}
