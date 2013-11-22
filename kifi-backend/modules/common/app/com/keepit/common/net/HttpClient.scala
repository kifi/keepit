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
import com.keepit.common._
import com.keepit.common.strings._
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.common.logging.{Logging, AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError, StackTrace}
import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.controller.CommonHeaders
import com.keepit.common.zookeeper.ServiceDiscovery
import scala.xml._
import org.apache.commons.lang3.RandomStringUtils
import com.ning.http.util.AsyncHttpProviderUtils

case class NonOKResponseException(url: HttpUri, response: ClientResponse, requestBody: Option[Any] = None)
    extends Exception(s"[${url.service}] Bad Http Status on ${url.summary} body:[${requestBody.map(_.toString.abbreviate(30)).getOrElse("")}] status:${response.status} res [${response.body.toString.abbreviate(30)}]"){
  override def toString(): String = getMessage
}

case class LongWaitException(request: Request, response: ClientResponse, waitTime: Int, duration: Int, remoteTime: Int)
    extends Exception(s"[${request.httpUri.service}] Long Wait on ${request.httpUri.summary} tracking-id:${request.trackingId} total-time:${duration}ms remote-time:${remoteTime}ms wait-time:${waitTime}ms data-size:${response.bytes.length} status:${response.res.status}"){
  override def toString(): String = getMessage
}

case class SlowJsonParsingException(request: Request, response: ClientResponse, time: Int)
    extends Exception(s"[${request.httpUri.service}] Slow JSON parsing on ${request.httpUri.summary} tracking-id:${request.trackingId} time:${time}ms data-size:${response.bytes.length}"){
  override def toString(): String = getMessage
}

trait HttpUri {
  val serviceInstanceOpt: Option[ServiceInstance] = None
  def url: String
  def service: String = ""
  def summary: String = url.abbreviate(30)
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
    airbrake: Provider[AirbrakeNotifier],
    accessLog: AccessLog,
    serviceDiscovery: ServiceDiscovery,
    silentFail: Boolean = false) extends HttpClient with Logging {

  private val validResponseClass = 2

  override val defaultFailureHandler: FailureHandler = { req =>
    {
      case e: Throwable =>
        val remoteInstance = req.httpUri.serviceInstanceOpt
        val al = accessLog.add(req.timer.done(
          result = "fail",
          query = req.queryString,
          url = req.url,
          remoteServiceType = remoteInstance.map(_.remoteService.serviceType.shortName).getOrElse(null),
          remoteServiceId = remoteInstance.map(_.id.id.toString).getOrElse(null),
          trackingId = req.trackingId,
          error = e.toString))
        val fullException = req.tracer.withCause(e)
        airbrake.get.notify(
          AirbrakeError.outgoing(
            exception = fullException,
            request = req.req,
            message = s"[${remoteServiceString(req)}] calling ${req.httpUri.summary} after ${al.duration}ms"
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
  private def req(url: HttpUri): Request = new Request(WS.url(url.url).withTimeout(timeout), url, headers, accessLog, serviceDiscovery)

  private def res(request: Request, response: Response, requestBody: Option[Any] = None): ClientResponse = {
    val clientResponse = new ClientResponseImpl(request, response, airbrake)
    if (response.status / 100 != validResponseClass) {
      val exception = new NonOKResponseException(request.httpUri, clientResponse, requestBody)
      if (silentFail) log.error(s"fail on $request => $clientResponse", exception)
      else throw exception
    }
    clientResponse
  }

  def withTimeout(timeout: Int): HttpClient = copy(timeout = timeout)

  private def report(reqRes: (Request, Future[Response]), onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = {
    val (request, responseFuture) = reqRes
    responseFuture.map(r => res(request, r))(immediate) tap { f =>
      f.onFailure(onFailure(request) orElse defaultFailureHandler(request)) (immediate)
      f.onSuccess {
        case response: ClientResponse => logSuccess(request, response)
        case unknown => airbrake.get.notify(s"Unknown object in http client onSuccess: $unknown on $request")
      } (immediate)
    }
  }

  private def logSuccess(request: Request, res: ClientResponse): Unit = {
    val remoteLeader = res.res.header(CommonHeaders.IsLeader).getOrElse(null)
    val remoteTime: Int = res.res.header(CommonHeaders.ResponseTime).map(_.toInt).getOrElse(AccessLogTimer.NoIntValue)
    val remoteInstance = request.httpUri.serviceInstanceOpt
    val e = accessLog.add(request.timer.done(
        remoteTime = remoteTime,
        remoteLeader = remoteLeader,
        result = "success",
        query = request.queryString,
        remoteServiceType = remoteInstance.map(_.remoteService.serviceType.shortName).getOrElse(null),
        remoteServiceId = remoteInstance.map(_.id.id.toString).getOrElse(null),
        url = request.url,
        trackingId = request.trackingId,
        statusCode = res.res.status,
        dataSize = res.bytes.length))

    e.waitTime map { waitTime =>
      if (waitTime > 1000) {//ms
        val exception = request.tracer.withCause(LongWaitException(request, res, waitTime, e.duration, remoteTime))
        airbrake.get.notify(
          AirbrakeError.outgoing(
            request = request.req,
            exception = exception
          )
        )
      }
    }
  }

  private def remoteServiceString(request: Request) =
    s"${request.httpUri.serviceInstanceOpt.map{i => i.remoteService.serviceType.shortName + i.id}.getOrElse("NA")}"
}

//This request class is not reusable for more then one call
class Request(val req: WSRequestHolder, val httpUri: HttpUri, headers: List[(String, String)], accessLog: AccessLog, serviceDiscovery: ServiceDiscovery) extends Logging {

  val trackingId = RandomStringUtils.randomAlphanumeric(5)
  val instance = serviceDiscovery.thisInstance
  private val headersWithTracking =
    (CommonHeaders.TrackingId, trackingId) ::
    (CommonHeaders.LocalServiceType, instance.map(_.remoteService.serviceType.shortName).getOrElse("NotAnnounced")) ::
    (CommonHeaders.LocalServiceId, instance.map(_.id.id.toString).getOrElse("NotAnnounced")) ::
    headers
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
  def bytes: Array[Byte]
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
}

class ClientResponseImpl(val request: Request, val res: Response, airbrake: Provider[AirbrakeNotifier]) extends ClientResponse {

  override def toString: String = s"ClientResponse with [status: $status, body: $body]"

  def status: Int = res.status

  lazy val bytes: Array[Byte] = res.ahcResponse.getResponseBodyAsBytes()

  lazy val body: String = {
    val ahcResponse = res.ahcResponse

    //+ the following is copied from play.api.libs.ws.WS
    // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
    // explicitly set, while Plays default encoding is UTF-8.  So, use UTF-8 if charset is not explicitly
    // set and content type is not text/*, otherwise default to ISO-8859-1
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    val charset = Option(AsyncHttpProviderUtils.parseCharset(contentType)).getOrElse {
      if (contentType.startsWith("text/"))
        AsyncHttpProviderUtils.DEFAULT_CHARSET
      else
        "utf-8"
    }
    //-
    new String(bytes, charset)
  }

  lazy val json: JsValue = {
    try {
      val startTime = System.currentTimeMillis
      val json = Json.parse(bytes)
      val jsonTime = System.currentTimeMillis - startTime

      if (jsonTime > 200 && !request.httpUri.url.contains("/internal/shoebox/database/getIndexable")) {//ms
        val exception = request.tracer.withCause(SlowJsonParsingException(request, this, jsonTime.toInt))
        airbrake.get.notify(
          AirbrakeError.outgoing(
            request = request.req,
            exception = exception
          )
        )
      }
      json
    } catch {
      case e: Throwable =>
        println("bad res: %s".format(body))
        throw e
    }
  }

  def xml: NodeSeq = {
    try {
      res.xml
    } catch {
      case e: Throwable =>
        println("bad res: %s".format(body))
        throw e
    }
  }
}

