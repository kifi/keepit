package com.keepit.common.service

import scala.concurrent.Future
import scala.util.Random

import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ServiceUnavailableException, ClientResponse, HttpClient, HttpUri}
import com.keepit.common.routes._
import com.keepit.common.zookeeper.{ServiceCluster, ServiceInstance}
import com.keepit.common.KestrelCombinator
import com.keepit.common.strings._

import java.net.ConnectException

import play.api.libs.json.{JsNull, JsValue}
import play.api.libs.concurrent.Execution.Implicits._

class ServiceNotAvailableException(serviceType: ServiceType)
  extends Exception(s"Service of type ${serviceType.name} is not available")

object ServiceClient {
  val MaxUrlLength = 1000
}

class ServiceUri(val serviceInstance: ServiceInstance, protocol: String, port: Int, path: String)
    extends HttpUri {
  override val serviceInstanceOpt = Some(serviceInstance)
  override def summary: String = s"$service:${path.abbreviate(100)}"
  override def service: String = s"${serviceInstance.remoteService.serviceType.shortName}${serviceInstance.id.id.toString}"
  lazy val url: String = s"$protocol://${serviceInstance.instanceInfo.localHostname}:${port}$path"
}

trait ServiceClient extends Logging {
  protected def httpClient: HttpClient

  val serviceCluster: ServiceCluster
  val airbrakeNotifier: AirbrakeNotifier

  private def nextInstance(): ServiceInstance =
    serviceCluster.nextService().getOrElse(throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): ServiceUri = new ServiceUri(nextInstance(), protocol, port, path)

  protected def urls(path: String): Seq[HttpUri] =
    serviceCluster.allServices.filter(!_.thisInstance).map(new ServiceUri(_, protocol, port, path)) tap { uris =>
      if (uris.length == 0) log.warn("Broadcasting/Teeing to no-one!")
    }

  protected def call(call: ServiceRoute, body: JsValue = JsNull, attempts : Int = 2, timeout: Int = 5000): Future[ClientResponse] = {
    val respFuture = RetryFuture(attempts, { case t : ConnectException => true }){
      callUrl(call, url(call.url), body, ignoreFailure = true, timeout = timeout)
    }
    respFuture.onSuccess {
      case res: ClientResponse => if(!res.isUp) {
        res.request.httpUri.serviceInstanceOpt.map(_.reportServiceUnavailable())
      }
    }
    respFuture.onFailure {
      case sue: ServiceUnavailableException =>
        val msg = s"service ${sue.serviceUri.serviceInstance} is not available, reported ${sue.serviceUri.serviceInstance.reportedSentServiceUnavailableCount} times"
        log.error(msg, sue)
        sue.serviceUri.serviceInstance.reportServiceUnavailable()
        airbrakeNotifier.notify(AirbrakeError(
          exception = sue,
          message = Some(msg),
          url = Some(call.path)))
      case ex: Throwable =>
        val stringBody = body.toString
        airbrakeNotifier.notify(AirbrakeError(
          exception = ex,
          message = Some(
            s"can't call [${call.path}] " +
            s"with body: ${stringBody.abbreviate(30)} (${stringBody.size} chars), " +
            s"params: ${call.params.map(_.toString).mkString(",")}"),
          method = Some(call.method.toString),
          url = Some(call.path)))
    }
    respFuture
  }

  protected def callUrl(call: ServiceRoute, httpUri: HttpUri, body: JsValue, ignoreFailure: Boolean = false, timeout: Int = 5000): Future[ClientResponse] = {
    val url = httpUri.url
    if (url.length > ServiceClient.MaxUrlLength) {
      airbrakeNotifier.notify(AirbrakeError(
        message = Some(s"Request URI length ${url.length} > ${ServiceClient.MaxUrlLength}: $url"),
        method = Some(call.method.toString),
        url = Some(s"$url")))
    }
    if (ignoreFailure) {
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.withTimeout(timeout).getFuture(httpUri, httpClient.ignoreFailure)
        case c @ ServiceRoute(POST, _, _*) => httpClient.withTimeout(timeout).postFuture(httpUri, body, httpClient.ignoreFailure)
      }
    }
    else{
      call match {
        case c @ ServiceRoute(GET, _, _*) => httpClient.withTimeout(timeout).getFuture(httpUri)
        case c @ ServiceRoute(POST, _, _*) => httpClient.withTimeout(timeout).postFuture(httpUri, body)
      }
    }
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ClientResponse]] =
    urls(call.url) map { url =>
      log.info(s"[broadcast] Sending to $url: ${body.toString.take(120)}")
      callUrl(call, url, body)
    }

  protected def tee(call: ServiceRoute, body: JsValue = JsNull, teegree: Int = 2): Future[ClientResponse] = {
    val futures = Random.shuffle(urls(call.url)).take(teegree).map(callUrl(call, _, body)) //need to shuffle
    Future.firstCompletedOf(futures)
  }
}
