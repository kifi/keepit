package com.keepit.common.service

import scala.concurrent.Future

import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ClientResponse, HttpClient, HttpUri}
import com.keepit.common.routes._
import com.keepit.common.zookeeper.{ServiceCluster, ServiceInstance}
import com.keepit.common.KestrelCombinator
import java.net.ConnectException
import play.api.libs.json.{JsNull, JsValue}
import play.api.libs.concurrent.Execution.Implicits._

class ServiceNotAvailableException(serviceType: ServiceType)
  extends Exception(s"Service of type ${serviceType.name} is not available")

object ServiceClient {
  val MaxUrlLength = 1000
}

class ServiceUri(serviceInstance: ServiceInstance, protocol: String, port: Int, path: String)
    extends HttpUri {
  override val serviceInstanceOpt = Some(serviceInstance)
  lazy val url = s"${protocol}://${serviceInstance.instanceInfo.localHostname}:${port}${path}"
}

trait ServiceClient extends Logging {
  protected def httpClient: HttpClient

  val serviceCluster: ServiceCluster
  val airbrakeNotifier: AirbrakeNotifier

  private def nextInstance(): ServiceInstance =
    serviceCluster.nextService.getOrElse(throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): HttpUri = new ServiceUri(nextInstance(), protocol, port, path)

  protected def urls(path: String): Seq[HttpUri] =
    serviceCluster.allServices.filter(!_.thisInstance).map(new ServiceUri(_, protocol, port, path)) tap { uris =>
      if (uris.length == 0) log.warn("Broadcasting to no-one!")
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

  protected def callUrl(call: ServiceRoute, httpUri: HttpUri, body: JsValue, ignoreFailure: Boolean = false): Future[ClientResponse] = {
    val url = httpUri.url
    if (url.length > ServiceClient.MaxUrlLength) {
      airbrakeNotifier.notify(AirbrakeError(
        message = Some(s"Request URI length ${url.length} > ${ServiceClient.MaxUrlLength}: $url"),
        method = Some(call.method.toString),
        url = Some(s"$url")))
    }
    if (ignoreFailure) {
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(httpUri, httpClient.ignoreFailure)
        case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(httpUri, body, httpClient.ignoreFailure)
      }
    }
    else{
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(httpUri)
        case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(httpUri, body)
      }
    }
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ClientResponse]] =
    urls(call.url) map { url =>
      log.info(s"[broadcast] Sending to $url: ${body.toString.take(120)}")
      callUrl(call, url, body)
    }
}
