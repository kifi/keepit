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
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck
import scala.xml._

case class NonOKResponseException(url: String, response: ClientResponse, requestBody: Option[Any] = None)
    extends Exception(s"Requesting $url ${requestBody.map{b => b.toString}}, got a ${response.status}. Body: ${response.body}")

trait HttpClient {

  val defaultOnFailure: String => PartialFunction[Throwable, Unit]

  val ignoreFailure: String => PartialFunction[Throwable, Unit] = {
    s: String => {
      case ex: Throwable =>
    }
  }

  def get(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def getFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def post(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def postFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def postXml(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def postXmlFuture(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def putFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def deleteFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def longTimeout(): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(
    timeout: Long = 2,
    timeoutUnit: TimeUnit = TimeUnit.SECONDS,
    headers: Seq[(String, String)] = List(),
    healthcheckPlugin: HealthcheckPlugin) extends HttpClient {

  private val validResponseClass = 2

  implicit val duration = Duration(timeout, timeoutUnit)

  override val defaultOnFailure: String => PartialFunction[Throwable, Unit] = { url =>
    {
      case cause: ConnectException =>
        if (healthcheckPlugin.isWarm) {
          val ex = new ConnectException(s"${cause.getMessage}. Requesting $url.").initCause(cause)
          healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        }
      case ex: Exception =>
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
    }
  }


  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(getFuture(url, onFailure))

  def getFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.get().map { response =>
      val cr = res(request, response)
      if (response.status / 100 != validResponseClass) {
        throw new NonOKResponseException(url, cr)
      }
      cr
    }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def post(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(postFuture(url, body, onFailure))

  def postFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.post(body).map { response =>
      val cr = res(request, response)
      if (response.status / 100 != validResponseClass) {
        throw new NonOKResponseException(url, cr, Some(body))
      }
      cr
    }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def postXml(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(postXmlFuture(url, body, onFailure))

  def postXmlFuture(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.post(body).map { response =>
      val cr = res(request, response)
      if (response.status / 100 != validResponseClass) {
        throw new NonOKResponseException(url, cr, Some(body))
      }
      cr
    }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse =
    await(putFuture(url, body, onFailure))

  def putFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.put(body).map { response =>
      val cr = res(request, response)
      if (response.status / 100 != validResponseClass) {
        throw new NonOKResponseException(url, cr, Some(body))
      }
      cr
    }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(deleteFuture(url, onFailure))

  def deleteFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.delete().map { response =>
      val cr = res(request, response)
      if (response.status / 100 != validResponseClass) {
        throw new NonOKResponseException(url, cr)
      }
      cr
    }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  private def await[A](future: Future[A]): A = Await.result(future, Duration(timeout, timeoutUnit))
  private def req(url: String): Request = new Request(WS.url(url).withHeaders(headers: _*))
  private def res(request: Request, response: Response): ClientResponse = new ClientResponseImpl(request, response)

  def longTimeout(): HttpClientImpl = copy(timeout = 2, timeoutUnit = TimeUnit.MINUTES)
}

private[net] class Request(wsRequest: WSRequestHolder) extends Logging {
  def get() = {
    val start = System.currentTimeMillis
    val res = wsRequest.get()
    res.onComplete { resTry =>
      logResponse(start, "GET", resTry.isSuccess)
    }
    res
  }

  def put(body: JsValue) = {
    val start = System.currentTimeMillis
    val res = wsRequest.put(body)
    res.onComplete { resTry =>
      logResponse(start, "PUT", resTry.isSuccess)
    }
    res
  }

  def post(body: JsValue) = {
    val start = System.currentTimeMillis
    val res = wsRequest.post(body)
    res.onComplete { resTry =>
      logResponse(start, "POST", resTry.isSuccess)
    }
    res
  }

  def post(body: NodeSeq) = {
    val start = System.currentTimeMillis
    val res = wsRequest.post(body)
    res.onComplete { resTry =>
      logResponse(start, "POST", resTry.isSuccess)
    }
    res
  }

  def delete() = {
    val start = System.currentTimeMillis
    val res = wsRequest.delete()
    res.onComplete { resTry =>
      logResponse(start, "DELETE", resTry.isSuccess)
    }
    res
  }

  private def logResponse(startTime: Long, method: String, isSuccess: Boolean) =
    log.info(s"""[${System.currentTimeMillis - startTime}ms] [$method] ${wsRequest.url} [${wsRequest.queryString map {case (k, v) => s"$k=$v"} mkString "&"}] (success = $isSuccess)""")
}

trait ClientResponse {
  def body: String
  def json: JsValue
  def status: Int
}

class ClientResponseImpl(val request: Request, val response: Response) extends ClientResponse {

  override def status: Int = response.status

  override def body: String = response.body

  override def json: JsValue = {
    try {
      response.json
    } catch {
      case e: Throwable =>
        println("bad response: %s".format(response.body.toString()))
        throw e
    }
  }
}
