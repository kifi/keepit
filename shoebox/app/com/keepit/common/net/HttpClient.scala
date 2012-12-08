package com.keepit.common.net

import play.api.libs.ws._
import play.mvc._
import play.api.libs.json._
import java.util.concurrent.TimeUnit
import play.api.libs.ws.WS.WSRequestHolder
import play.api.mvc.Request
import scala.collection.immutable.Seq
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Await

trait HttpClient {

  def get(url: String): ClientResponse

  def getPromise(url: String): Future[ClientResponse]

  def post(url: String, body: JsValue): ClientResponse

  def postPromise(url: String, body: JsValue): Future[ClientResponse]

  def longTimeout(): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(val timeout: Long = 2, val timeoutUnit: TimeUnit = TimeUnit.SECONDS, val headers: Seq[(String, String)] = List()) extends HttpClient {

  implicit val duration = Duration(timeout, timeoutUnit)

  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: String): ClientResponse = await(getPromise(url))

  def getPromise(url: String): Future[ClientResponse] = {
    val request = req(url)
    request.get() map { response =>
      res(request, response)
    }
  }

  def post(url: String, body: JsValue): ClientResponse = await(postPromise(url, body))

  def postPromise(url: String, body: JsValue): Future[ClientResponse] = {
    val request = req(url)
    request.post(body) map { response =>
      res(request, response)
    }
  }

  private def await[A](promise: Future[A]): A = Await.result(promise, duration)
  private def req(url: String): WSRequestHolder = WS.url(url).withHeaders(headers: _*)
  private def res(request: WSRequestHolder, response: Response): ClientResponse = new ClientResponseImpl(request, response)

  def longTimeout(): HttpClientImpl = copy(timeout = 2, timeoutUnit = TimeUnit.MINUTES)
}

trait ClientResponse {
  def body: String
  def json: JsValue
  def status: Int
}

class ClientResponseImpl(val request: WSRequestHolder, val response: Response) extends ClientResponse {

  private def verify(): ClientResponseImpl = if (response.status != Http.Status.OK) {
      throw new Exception("Error getting response. Response status is %s, request was: %s".format(response.status, request.url))
  } else this

  override def status: Int = verify().response.status

  override def body: String = verify().response.body

  override def json: JsValue = {
    verify()
    try {
      response.json
    } catch {
      case e: Throwable =>
        println("bad response: %s".format(response.body.toString()))
        throw e
    }
  }
}
