package com.keepit.common.service

import scala.concurrent.Future
import com.keepit.common.net.{ClientResponse, HttpClient}
import play.api.libs.json.{JsNull, JsValue}
import play.api.mvc.Call
import com.keepit.common.routes._

trait ServiceClient {
  protected def httpClient: HttpClient

  val serviceType: ServiceType
  val host: String
  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${host}:${port}${path}"

  protected def call(call: ServiceRoute, body: JsValue = JsNull): Future[ClientResponse] = call match {
    case c @ ServiceRoute(GET, _, _) => httpClient.getFuture(url(c.toString))
    case c @ ServiceRoute(POST, _, _) => httpClient.postFuture(url(c.toString), body)
  }
}
