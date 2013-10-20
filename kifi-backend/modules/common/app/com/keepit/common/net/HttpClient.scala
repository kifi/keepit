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
import com.keepit.common.zookeeper.ServiceInstance
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

case class NonOKResponseException(url: HttpUri, response: ClientResponse, requestBody: Option[Any] = None)
    extends Exception(s"Requesting $url ${requestBody.map{b => b.toString}}, got a ${response.status}. Body: ${response.body}"){

  override def toString: String =
    s"NonOKResponseException[url: $url, Response: $response body:${requestBody.map(b => b.toString).getOrElse("NA")}]"
}

case class LongWaitException(url: HttpUri, response: Response, waitTime: Int)
    extends Exception(s"Requesting $url got a ${response.status} with wait time $waitTime"){
}

trait HttpUri {
  val serviceInstanceOpt: Option[ServiceInstance] = None
  def url: String
  override def equals(obj: Any) = obj.asInstanceOf[HttpUri].url == url
  override def toString(): String = s"$url for service $serviceInstanceOpt"
}

case class DirectUrl(val url: String) extends HttpUri

trait HttpClient {

  type FailureHandler = Request => PartialFunction[Throwable, Unit]

  val defaultFailureHandler: FailureHandler

  val ignoreFailure: FailureHandler = {
    s: Request => {
      case ex: Throwable =>
    }
  }

  def get(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def getFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def postFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def postText(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def postTextFuture(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def postXml(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def postXmlFuture(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def put(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def putFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def delete(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse
  def deleteFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse]

  def withTimeout(timeout: Int): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(
    timeout: Int = 5000,
    headers: List[(String, String)] = List(),
    healthcheckPlugin: HealthcheckPlugin,
    airbrake: Provider[AirbrakeNotifier],
    services: FortyTwoServices,
    accessLog: AccessLog,
    silentFail: Boolean = false) extends HttpClient with Logging {

  private val validResponseClass = 2

  override val defaultFailureHandler: FailureHandler = { req =>
    {
      case e: Throwable =>
        val al = accessLog.add(req.timer.done(
          result = "fail",
          query = req.queryString,
          url = req.url,
          //Its a bit strange, but in this case we rather pass null to be consistent with the api
          //taking only the first 200 chars of the body
          trackingId = req.trackingId,
          error = e.toString))
        airbrake.get.notify(
          AirbrakeError.outgoing(
            exception = req.tracer.withCause(e),
            request = req.req,
            message = s"${al.error}: error handling url ${al.url}"
          )
        )
    }
  }

  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(getFuture(url, onFailure))

  def getFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.get()}, onFailure)

  def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(postFuture(url, body, onFailure))

  def postFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.post(body)}, onFailure)

  def postText(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = await(postTextFuture(url, body, onFailure))

  def postTextFuture(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.post(body)}, onFailure)

  def postXml(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = await(postXmlFuture(url, body, onFailure))

  def postXmlFuture(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.post(body)}, onFailure)

  def put(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(putFuture(url, body, onFailure))

  def putFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.put(body)}, onFailure)

  def delete(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(deleteFuture(url, onFailure))

  def deleteFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url) tapWith {_.delete()}, onFailure)

  private def await[A](future: Future[A]): A = Await.result(future, Duration(timeout, TimeUnit.MILLISECONDS))
  private def req(url: HttpUri): Request = new Request(WS.url(url.url).withTimeout(timeout), url, headers, services, accessLog)

  private def res(request: Request, response: Response, requestBody: Option[Any] = None): ClientResponse = {
    val clientResponse = new ClientResponseImpl(request, response)
    if (response.status / 100 != validResponseClass) {
      val exception = new NonOKResponseException(request.httpUri, clientResponse, requestBody)
      if (silentFail) log.error(s"fail on $request => $clientResponse", exception)
      else throw exception
    }
    clientResponse
  }

  def withTimeout(timeout: Int): HttpClient = copy(timeout = timeout)

  private def report(reqRes: (Request, Future[Response]), onFailure: => FailureHandler = defaultFailureHandler):
      Future[ClientResponse] = reqRes match { case (request, responseFuture) =>
    responseFuture.map(r => res(request, r))(immediate) tap { f =>
      f.onFailure(onFailure(request) orElse defaultFailureHandler(request)) (immediate)
      f.onSuccess {
        case response: ClientResponse => logSuccess(request, response)
        case unknown => airbrake.get.notify(AirbrakeError(message = Some(s"Unknown object in http client onSuccess: $unknown on $request")))
      } (immediate)
    }
  }

  private def logSuccess(request: Request, res: ClientResponse): Unit = {
    //todo(eishay): the interesting part is the remote service and node id, to be logged
    val remoteHost = res.res.header(CommonHeaders.LocalHost).getOrElse("NA")
    val remoteTime = res.res.header(CommonHeaders.ResponseTime).map(_.toInt).getOrElse(AccessLogTimer.NoIntValue)
    val e = accessLog.add(request.timer.done(
        remoteTime = remoteTime,
        result = "success",
        query = request.queryString,
        url = request.url,
        //Its a bit strange, but in this case we rather pass null to be consistent with the api
        //taking only the first 200 chars of the body
        trackingId = request.trackingId,
        statusCode = res.res.status))

    e.waitTime map {waitTime =>
      if (waitTime > 200) {//ms
        val exception = request.tracer.withCause(LongWaitException(request.httpUri, res.res, waitTime))
        val remoteService = s"${request.httpUri.serviceInstanceOpt.map{i => i.remoteService.serviceType.name + i.id}.getOrElse("NA")}"
        airbrake.get.notify(
          AirbrakeError.outgoing(
            request = request.req,
            exception = exception,
            message = s"[$remoteService] wait time $waitTime for ${request.httpUri.url}")
        )
      }
    }
  }
}

//This request class is not reusable for more then one call
class Request(val req: WSRequestHolder, val httpUri: HttpUri, headers: List[(String, String)], services: FortyTwoServices, accessLog: AccessLog) extends Logging {

  val trackingId = RandomStringUtils.randomAlphanumeric(5)
  private val headersWithTracking =
    (CommonHeaders.TrackingId, trackingId) :: (CommonHeaders.LocalService, services.currentService.toString) :: headers
  private val wsRequest = req.withHeaders(headersWithTracking: _*)
  lazy val queryString = (wsRequest.queryString map {case (k, v) => s"$k=$v"} mkString "&").trim match {
    case "" => null
    case str => str
  }
  lazy val url = wsRequest.url

  var timer: AccessLogTimer = _
  var tracer: StackTrace = _

  private def record() = {
    timer = accessLog.timer(HTTP_OUT)
    tracer = new StackTrace()
  }

  def get() =               {record(); wsRequest.get()}
  def put(body: JsValue) =  {record(); wsRequest.put(body)}
  def post(body: String) =  {record(); wsRequest.post(body)}
  def post(body: JsValue) = {record(); wsRequest.post(body)}
  def post(body: NodeSeq) = {record(); wsRequest.post(body)}
  def delete() =            {record(); wsRequest.delete()}
}

trait ClientResponse {
  def res: Response
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
}

class ClientResponseImpl(val request: Request, val res: Response) extends ClientResponse {

  override def toString: String = s"ClientResponse with [status: $status, body: $body]"

  def status: Int = res.status

  def body: String = res.body

  def json: JsValue = {
    try {
      res.json
    } catch {
      case e: Throwable =>
        println("bad res: %s".format(res.body.toString()))
        throw e
    }
  }

  def xml: NodeSeq = {
    try {
      res.xml
    } catch {
      case e: Throwable =>
        println("bad res: %s".format(res.body.toString()))
        throw e
    }
  }
}
