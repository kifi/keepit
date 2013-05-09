package com.keepit.common.net

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.ws._
import play.mvc._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck

case class NonOKResponseException(message: String) extends Exception(message)

trait HttpClient {

  def get(url: String): ClientResponse

  def getFuture(url: String): Future[ClientResponse]

  def post(url: String, body: JsValue): ClientResponse

  def postFuture(url: String, body: JsValue): Future[ClientResponse]

  def longTimeout(): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(val timeout: Long = 2, val timeoutUnit: TimeUnit = TimeUnit.SECONDS, val headers: Seq[(String, String)] = List(), healthcheckPlugin: HealthcheckPlugin) extends HttpClient {

  implicit val duration = Duration(timeout, timeoutUnit)

  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: String): ClientResponse = await(getFuture(url))

  def getFuture(url: String): Future[ClientResponse] = {
    val request = req(url)
    val startedAt = System.currentTimeMillis()
    val result = request.get().map { response =>
      if(response.status != Http.Status.OK) {
        val ex = new NonOKResponseException(s"Requesting $url, got a ${response.status}. Body: ${response.body}")
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
      }
      res(request, response)
    }
    result.onFailure {
      case cause: ConnectException =>
        val duration = System.currentTimeMillis() - startedAt
        val ex = new ConnectException(s"${cause.getMessage} (took $duration ms). Requesting $url.").initCause(cause)
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
    }
    result
  }

  def post(url: String, body: JsValue): ClientResponse = await(postFuture(url, body))

  def postFuture(url: String, body: JsValue): Future[ClientResponse] = {
    val request = req(url)
    val startedAt = System.currentTimeMillis()
    val result = request.post(body).map { response =>
      if(response.status != Http.Status.OK) {
        val ex = new NonOKResponseException(s"Requesting $url, got a ${response.status}. Body: ${response.body}")
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
      }
      res(request, response)
    }
    result.onFailure {
      case cause: ConnectException =>
        val duration = System.currentTimeMillis() - startedAt
        val ex = new ConnectException(s"${cause.getMessage} (took $duration ms). Requesting $url with body $body.").initCause(cause)
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
    }
    result
  }

  private def await[A](future: Future[A]): A = Await.result(future, Duration(timeout, timeoutUnit))
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

  override def status: Int = response.status

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
