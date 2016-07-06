package com.keepit.common.net

import com.google.inject.Provider
import play.api.Play
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.libs.ws._
import play.mvc._
import com.keepit.common.core._
import com.keepit.common.strings._
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.common.logging.{ AccessLogEvent, Logging, AccessLogTimer, AccessLog }
import com.keepit.common.logging.Access._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError, StackTrace }
import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.controller.{ MidFlightRequests, CommonHeaders }
import com.keepit.common.zookeeper.ServiceDiscovery
import scala.xml._
import play.mvc.Http.Status
import com.keepit.common.service.{ RequestConsolidator, ServiceUri }
import java.util.Random
import com.keepit.common.util.TrackingId
import play.api.Play.current

case class NonOKResponseException(url: HttpUri, response: ClientResponse, requestBody: Option[Any] = None)
    extends Exception(s"[${url.service}] ERR on ${url.summary} stat:${response.status} - ${response.body.toString.abbreviate(1000).replaceAll("\n", " ")}]") {
  override def toString(): String = getMessage
}

case class ServiceUnavailableException(serviceUri: ServiceUri, response: ClientResponse, duration: Int)
    extends Exception(s"[${serviceUri.service}] ServiceUnavailable Http Status on ${serviceUri.summary}] total-time:${duration}ms is more then 20sec?") {
  override def toString(): String = getMessage
}

case class LongWaitException(request: Request, response: ClientResponse, initTime: Int, waitTime: Int, duration: Int, remoteTime: Int, midFlightRequestCount: Int, listOfMidFlightRequests: String, remoteMidFlightRequestCount: Option[Int])
    extends Exception(
      s"[${request.httpUri.service}] Long Wait on ${request.httpUri.summary} " +
        s"tracking-id:${request.trackingId} parse-time:${response.parsingTime.getOrElse("NA")} total-time:${duration}ms init-time:${initTime}ms remote-time:${remoteTime}ms " +
        s"wait-time:${waitTime}ms data-size:${response.bytes.length} status:${response.res.status} remoteMidFlight[$remoteMidFlightRequestCount] localMidFlight[$midFlightRequestCount]:$listOfMidFlightRequests") {
  override def toString(): String = getMessage
}

trait HttpUri {
  val serviceInstanceOpt: Option[ServiceInstance] = None
  def url: String
  def service: String = ""
  def summary: String = url.abbreviate(100)
  override def equals(obj: Any) = obj.asInstanceOf[HttpUri].url == url
  override def toString(): String = s"$url to ${serviceInstanceOpt.map(_.remoteService.amazonInstanceInfo.getName)}"
}

case class DirectUrl(val url: String) extends HttpUri

case class CallTimeouts(responseTimeout: Option[Int] = None, maxWaitTime: Option[Int] = None, maxJsonParseTime: Option[Int] = None) {
  def overrideWith(callTimeouts: CallTimeouts): CallTimeouts = CallTimeouts(
    responseTimeout = callTimeouts.responseTimeout.orElse(responseTimeout),
    maxWaitTime = callTimeouts.maxWaitTime.orElse(maxWaitTime),
    maxJsonParseTime = callTimeouts.maxJsonParseTime.orElse(maxJsonParseTime))
}

object CallTimeouts {
  val UseDefaults = CallTimeouts(None, None, None)
}

trait HttpClient {

  type FailureHandler = Request => PartialFunction[Throwable, Unit]

  val defaultFailureHandler: FailureHandler

  val ignoreFailure: FailureHandler = {
    s: Request =>
      {
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

  def withTimeout(callTimeouts: CallTimeouts): HttpClient

  def withHeaders(hdrs: (String, String)*): HttpClient
}

case class HttpClientImpl(
    callTimeouts: CallTimeouts,
    headers: List[(String, String)] = List(),
    airbrake: Provider[AirbrakeNotifier],
    fastJsonParser: FastJsonParser,
    accessLog: AccessLog,
    serviceDiscovery: ServiceDiscovery,
    midFlightRequests: MidFlightRequests,
    silentFail: Boolean = false) extends HttpClient with Logging {

  private val validResponseClass = 2

  private val dontAlertTooMuch = new RequestConsolidator[Unit, Unit](10.seconds)

  override val defaultFailureHandler: FailureHandler = { req =>
    {
      case e: Throwable =>
        dontAlertTooMuch(()) { _ =>
          val remoteInstance = req.httpUri.serviceInstanceOpt
          val duration: Int = accessLog.add(req.timer.done(
            result = "fail",
            query = req.queryString,
            url = req.url,
            remoteServiceType = remoteInstance.map(_.remoteService.serviceType.shortName).getOrElse(null),
            remoteServiceId = remoteInstance.map(_.id.id.toString).getOrElse(null),
            trackingId = req.trackingId,
            error = e.toString)).duration
          val fullException = req.tracer.withCause(e)
          val error = AirbrakeError.outgoing(
            exception = fullException,
            request = req.req,
            message = s"[${remoteServiceString(req)}] calling ${req.httpUri.summary} after ${duration}ms"
          )
          if (e.getMessage.contains("Too many open files")) airbrake.get.panic(error)
          else airbrake.get.notify(error)
          Future.successful(())
        }
    }
  }

  def withHeaders(hdrs: (String, String)*): HttpClient = this.copy(headers = headers ++ hdrs)

  def get(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(getFuture(url, onFailure))

  def getFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.get(), onFailure)

  def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(postFuture(url, body, onFailure))

  def postFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.post(body), onFailure)

  def postText(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = await(postTextFuture(url, body, onFailure))

  def postTextFuture(url: HttpUri, body: String, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.post(body), onFailure)

  def postXml(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = await(postXmlFuture(url, body, onFailure))

  def postXmlFuture(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.post(body), onFailure)

  def put(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(putFuture(url, body, onFailure))

  def putFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.put(body), onFailure)

  def delete(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse =
    await(deleteFuture(url, onFailure))

  def deleteFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] =
    report(req(url), _.delete(), onFailure)

  private def await[A](future: Future[A]): A = Await.result(future, Duration(callTimeouts.responseTimeout.get, TimeUnit.MILLISECONDS))
  private def req(url: HttpUri): Request = new Request(WS.url(url.url).withRequestTimeout(callTimeouts.responseTimeout.get), url, headers, accessLog, serviceDiscovery)

  private def res(request: Request, response: WSResponse, requestBody: Option[Any] = None): ClientResponse = {
    val clientResponse = new ClientResponseImpl(request, response, airbrake, fastJsonParser, callTimeouts.maxJsonParseTime.get)
    val status = response.status
    if (status / 100 != validResponseClass) {
      val error: Option[Exception] = if (status == Status.SERVICE_UNAVAILABLE) {
        request.httpUri match {
          case d: DirectUrl =>
            val msg = s"external service ${d.summary} is not available"
            log.error(msg)
            Some(new NonOKResponseException(request.httpUri, clientResponse, requestBody))
          case s: ServiceUri =>
            val count = s.serviceInstance.reportServiceUnavailable()
            val msg = s"service ${s.serviceInstance} is not available, reported $count times"
            log.error(msg)
            if (count > 5) {
              // if remote service is reporting shutdown, don’t immediately throw an exception.
              // duration is expected to be more then 20sec!
              val err = AirbrakeError(message = Some(msg), url = Some(s.summary))
              airbrake.get.notify(err)
              Some(new ServiceUnavailableException(request.httpUri.asInstanceOf[ServiceUri], clientResponse, request.timer.done().duration))
            } else {
              None
            }
        }
      } else {
        Some(new NonOKResponseException(request.httpUri, clientResponse, requestBody))
      }
      error foreach { exception =>
        if (silentFail) log.error(s"fail on $request => $clientResponse", exception)
        else throw exception
      }
    }
    clientResponse
  }

  def withTimeout(callTimeouts: CallTimeouts): HttpClient = {
    copy(callTimeouts = this.callTimeouts.overrideWith(callTimeouts))
  }

  private def report(request: Request, getResponse: Request => Future[WSResponse], onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = {
    getResponse(request).map(r => res(request, r))(immediate) tap { f =>
      f.onFailure(onFailure(request) orElse defaultFailureHandler(request))
      f.onSuccess {
        case response: ClientResponse => logSuccess(request, response)
        case unknown => airbrake.get.notify(s"Unknown object in http client onSuccess: $unknown on $request")
      }(immediate)
    }
  }

  private def logSuccess(request: Request, res: ClientResponse): Unit = {
    val remoteUp = res.isUp
    val remoteTime: Int = res.res.header(CommonHeaders.ResponseTime).map(_.toInt).getOrElse(AccessLogTimer.NoIntValue)
    val remoteMidFlightRequestCount: Option[Int] = res.res.header(CommonHeaders.MidFlightRequestCount).map(_.toInt)
    val remoteInstance = request.httpUri.serviceInstanceOpt
    val e = accessLog.add(request.timer.done(
      remoteTime = remoteTime,
      parsingTime = res.parsingTime.map(_.toInt),
      remoteUp = remoteUp.toString,
      result = "success",
      query = request.queryString,
      remoteServiceType = remoteInstance.map(_.remoteService.serviceType.shortName).getOrElse(null),
      remoteServiceId = remoteInstance.map(_.id.id.toString).getOrElse(null),
      url = request.url,
      trackingId = request.trackingId,
      statusCode = res.res.status,
      dataSize = res.bytes.length))

    e.waitTime map { waitTime =>
      if (waitTime > callTimeouts.maxWaitTime.get * serviceDiscovery.thisService.loadFactor && Play.maybeApplication.map(_.mode) == Some(play.api.Mode.Prod)) {
        val initTime = request.initTime.toInt
        val ex = LongWaitException(request, res, initTime, waitTime - initTime, e.duration, remoteTime, midFlightRequests.count, midFlightRequests.topRequests, remoteMidFlightRequestCount)
        ex.setStackTrace(new Array(0))
        val exception = request.tracer.withCause(ex)
        airbrake.get.notify(
          AirbrakeError.outgoing(
            request = request.req,
            exception = exception,
            aggregateOnly = true
          )
        )
      }
    }
  }

  private def remoteServiceString(request: Request) =
    s"${request.httpUri.serviceInstanceOpt.map { i => i.remoteService.serviceType.shortName + i.id }.getOrElse("NA")}"
}

//This request class is not reusable for more then one call
class Request(val req: WSRequestHolder, val httpUri: HttpUri, headers: List[(String, String)], accessLog: AccessLog, serviceDiscovery: ServiceDiscovery) extends Logging {

  val trackingId = TrackingId.get()
  val instance = serviceDiscovery.thisInstance
  private val headersWithTracking =
    (CommonHeaders.TrackingId, trackingId) ::
      (CommonHeaders.LocalServiceType, instance.map(_.remoteService.serviceType.shortName).getOrElse("NotAnnounced")) ::
      (CommonHeaders.LocalServiceId, instance.map(_.id.id.toString).getOrElse("NotAnnounced")) ::
      headers
  private val wsRequest = req.withHeaders(headersWithTracking: _*)
  lazy val queryString = (wsRequest.queryString map { case (k, v) => s"$k=$v" } mkString "&").trim match {
    case "" => null
    case str => str
  }
  lazy val url = wsRequest.url

  var timer: AccessLogTimer = _
  var tracer: StackTrace = _
  var initTime: Long = _

  @inline private[this] def startTimer() = {
    timer = accessLog.timer(HTTP_OUT)
  }
  @inline private[this] def timeInitiation() = {
    initTime = timer.laps
  }

  def get() = try { startTimer(); wsRequest.get() } finally { timeInitiation(); tracer = new StackTrace() }
  def put(body: JsValue) = try { startTimer(); wsRequest.put(body) } finally { timeInitiation(); tracer = new StackTrace() }
  def post(body: String) = try { startTimer(); wsRequest.post(body) } finally { timeInitiation(); tracer = new StackTrace() }
  def post(body: JsValue) = try { startTimer(); wsRequest.post(body) } finally { timeInitiation(); tracer = new StackTrace() }
  def post(body: NodeSeq) = try { startTimer(); wsRequest.post(body) } finally { timeInitiation(); tracer = new StackTrace() }
  def delete() = try { startTimer(); wsRequest.delete() } finally { timeInitiation(); tracer = new StackTrace() }
}

