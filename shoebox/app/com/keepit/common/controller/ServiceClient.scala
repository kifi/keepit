package com.keepit.common.controller

import scala.concurrent.Future

import com.keepit.common.net.{ClientResponse, HttpClient}

import play.api.libs.json.{JsNull, JsValue}
import play.api.mvc.Call

trait ServiceClient {
  protected def httpClient: HttpClient

  val serviceType: ServiceType
  val host: String
  val protocol: String = "http"
  val port: Int = 9000

  protected def url(path: String): String = s"${protocol}://${host}:${port}${path}"

  protected def call(call: Call, body: JsValue = JsNull): ClientResponse = call match {
    case Call("GET", path) => httpClient.get(url(path))
    case Call("POST", path) => httpClient.post(url(path), body)
    case Call(m, _) => throw new UnsupportedOperationException(s"Unsupported method $m")
  }

  protected def callFuture(call: Call, body: JsValue = JsNull): Future[ClientResponse] = call match {
    case Call("GET", path) => httpClient.getFuture(url(path))
    case Call("POST", path) => httpClient.postFuture(url(path), body)
    case Call(m, _) => throw new UnsupportedOperationException(s"Unsupported method $m")
  }
}
