package com.keepit.common.net

import com.google.inject.Provider

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.ws._
import play.mvc._
import com.keepit.common.logging.{Logging, AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError, HealthcheckPlugin, StackTrace}
import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.controller.CommonHeaders
import scala.xml._
import org.apache.commons.lang3.RandomStringUtils
import play.modules.statsd.api.Statsd
import com.keepit.common.service.FortyTwoServices

import play.api.Logger

case class NonOKResponseException(url: String, response: ClientResponse, requestBody: Option[Any] = None)
    extends Exception(s"Requesting $url ${requestBody.map{b => b.toString}}, got a ${response.status}. Body: ${response.body}"){

  override def toString: String =
    s"NonOKResponseException[url: $url, Response: $response body:${requestBody.map(b => b.toString).getOrElse("NA")}]"

}

case class LongWaitException(url: String, response: Response, waitTime: Int)
    extends Exception(s"Requesting $url got a ${response.status} with wait time $waitTime"){
}


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

  def postText(url: String, body: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def postTextFuture(url: String, body: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def postXml(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def postXmlFuture(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def putFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse
  def deleteFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse]

  def withTimeout(timeout: Int): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(
    timeout: Int = 10000,
    headers: List[(String, String)] = List(),
    healthcheckPlugin: HealthcheckPlugin,
    airbrake: Provider[AirbrakeNotifier],
    services: FortyTwoServices,
    accessLog: AccessLog) extends HttpClient {

  private val validResponseClass = 2

  override val defaultOnFailure: String => PartialFunction[Throwable, Unit] = { url =>
    {
      case cause: ConnectException =>
        if (healthcheckPlugin.isWarm) {
          val ex = new ConnectException(s"${cause.getMessage}. Requesting $url.").initCause(cause)
          airbrake.get.notify(ex)
        }
      case ex: Exception =>
        airbrake.get.notify(ex)
    }
  }

  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(getFuture(url, onFailure))

  def getFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.get() map { r => res(request, r) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def post(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(postFuture(url, body, onFailure))

  def postFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.post(body) map { r => res(request, r, Some(body)) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def postText(url: String, body: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(postTextFuture(url, body, onFailure))

  def postTextFuture(url: String, body: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.post(body) map { r => res(request, r, Some(body)) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def postXml(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = await(postXmlFuture(url, body, onFailure))

  def postXmlFuture(url: String, body: NodeSeq, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.post(body) map { r => res(request, r, Some(body)) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse =
    await(putFuture(url, body, onFailure))

  def putFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.put(body) map { r => res(request, r, Some(body)) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse =
    await(deleteFuture(url, onFailure))

  def deleteFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = {
    val request = req(url)
    val result = request.delete() map { r => res(request, r) }
    result.onFailure(onFailure(url) orElse defaultOnFailure(url))
    result
  }

  private def await[A](future: Future[A]): A = Await.result(future, Duration(timeout, TimeUnit.MILLISECONDS))
  private def req(url: String): Request = new Request(WS.url(url).withTimeout(timeout), headers, services, accessLog, airbrake)

  private def res(request: Request, response: Response, requestBody: Option[Any] = None): ClientResponse = {
    val cr = new ClientResponseImpl(request, response)
    if (response.status / 100 != validResponseClass) {
      throw new NonOKResponseException(request.req.url, cr, requestBody)
    }
    cr
  }

  def withTimeout(timeout: Int): HttpClientImpl = copy(timeout = timeout)
}

private[net] class Request(val req: WSRequestHolder, headers: List[(String, String)],
    services: FortyTwoServices, accessLog: AccessLog, airbrake: Provider[AirbrakeNotifier]) extends Logging {

  private val trackingId = RandomStringUtils.randomAlphanumeric(5)
  private val headersWithTracking =
    (CommonHeaders.TrackingId, trackingId) :: (CommonHeaders.LocalService, services.currentService.toString) :: headers
  private val wsRequest = req.withHeaders(headersWithTracking: _*)

  def get() = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.get()
    res.onComplete { resTry =>
      logResponse(timer, tracer, "GET", resTry, trackingId, None, wsRequest)
    }(immediate)
    res
  }

  def put(body: JsValue) = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.put(body)
    res.onComplete { resTry =>
      logResponse(timer, tracer, "PUT", resTry, trackingId, Some(body), wsRequest)
    }(immediate)
    res
  }

  def post(body: String) = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.post(body)
    res.onComplete { resTry =>
      logResponse(timer, tracer, "POST", resTry, trackingId, Some(body), wsRequest)
    }(immediate)
    res
  }

  def post(body: JsValue) = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.post(body)
    res.onComplete { resTry =>
      logResponse(timer, tracer, "POST", resTry, trackingId, Some(body), wsRequest)
    }(immediate)
    res
  }

  def post(body: NodeSeq) = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.post(body)
    res.onComplete { resTry =>
      logResponse(timer, tracer, "POST", resTry, trackingId, Some(body), wsRequest)
    }(immediate)
    res
  }

  def delete() = {
    val timer = accessLog.timer(HTTP_OUT)
    val tracer = new StackTrace()
    val res = wsRequest.delete()
    res.onComplete { resTry =>
      logResponse(timer, tracer, "DELETE", resTry, trackingId, None, wsRequest)
    }(immediate)
    res
  }

  private def logResponse(
        timer: AccessLogTimer, tracer: StackTrace, method: String, resTry: Try[Response],
        trackingId: String, body: Option[Any], req: WSRequestHolder): Unit = {
    //todo(eishay): the interesting part is the remote service and node id, to be logged
    val queryString = (wsRequest.queryString map {case (k, v) => s"$k=$v"} mkString "&").trim match {
      case "" => null
      case str => str
    }
    // waitTime map {t =>
    //   Statsd.timing(s"internalCall.remote.$remoteService.$remoteNodeId", t)
    //   Statsd.timing(s"internalCall.local.$localService.$localNodeId", t)
    // }
    val bodyTrimmed = body.map(_.toString.take(200)).getOrElse(null)
    resTry match {
      case Success(res) =>
        val remoteHost = res.header(CommonHeaders.LocalHost).getOrElse("NA")
        val remoteTime = res.header(CommonHeaders.ResponseTime).map(_.toInt).getOrElse(AccessLogTimer.NoIntValue)
        val e = accessLog.add(timer.done(
            remoteTime = remoteTime,
            result = "success",
            query = queryString,
            url = wsRequest.url,
            //Its a bit strange, but in this case we rather pass null to be consistent with the api
            //taking only the first 200 chars of the body
            body = bodyTrimmed,
            trackingId = trackingId,
            statusCode = res.status))

        e.waitTime map {waitTime =>
          if (waitTime > 100) {//ms
            val exception = tracer.withCause(LongWaitException(req.url, res, waitTime))
            airbrake.get.notify(
              AirbrakeError.outgoing(
                request = req,
                exception = exception,
                message = s"wait time $waitTime for ${accessLog.format(e)}")
            )
          }
        }
      case Failure(e) =>
        val al = accessLog.add(timer.done(
          result = "fail",
          query = queryString,
          url = wsRequest.url,
          //Its a bit strange, but in this case we rather pass null to be consistent with the api
          //taking only the first 200 chars of the body
          body = bodyTrimmed,
          trackingId = trackingId,
          error = e.toString))
        airbrake.get.notify(
          AirbrakeError.outgoing(
            exception = tracer.withCause(e),
            request = req,
            message = s"error handling $al"
          )
        )
    }
  }
}

trait ClientResponse {
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
}

class ClientResponseImpl(val request: Request, val response: Response) extends ClientResponse {

  override def toString: String = s"ClientResponse with [status: $status, body: $body]"

  def status: Int = response.status

  def body: String = response.body

  def json: JsValue = {
    try {
      response.json
    } catch {
      case e: Throwable =>
        println("bad response: %s".format(response.body.toString()))
        throw e
    }
  }

  def xml: NodeSeq = {
    try {
      response.xml
    } catch {
      case e: Throwable =>
        println("bad response: %s".format(response.body.toString()))
        throw e
    }
  }
}
