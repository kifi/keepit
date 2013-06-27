package com.keepit.common.service

import scala.concurrent.Future

import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{ClientResponse, HttpClient}
import com.keepit.common.routes._

import play.api.libs.json.{JsNull, JsValue}
import play.api.mvc.Call

trait ServiceClient extends Logging {
  protected def httpClient: HttpClient

  val serviceCluster: ServiceCluster

  private def nextHost(): String = serviceCluster.nextService.amazonInstanceInfo.localHostname

  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${nextHost()}:${port}${path}"

  protected def call(call: ServiceRoute, body: JsValue = JsNull): Future[ClientResponse] = call match {
    case c @ ServiceRoute(GET, _, _*) => httpClient.getFuture(url(c.url))
    case c @ ServiceRoute(POST, _, _*) => httpClient.postFuture(url(c.url), body)
  }
}
