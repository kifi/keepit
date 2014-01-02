package com.keepit.common.net

import com.google.inject.Provider
import play.api.libs.json._
import play.api.libs.ws._

import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}

import scala.xml._

import com.ning.http.util.AsyncHttpProviderUtils


case class SlowJsonParsingException(request: Request, response: ClientResponse, time: Long, tracking: JsonParserTrackingErrorMessage)
    extends Exception(s"[${request.httpUri.service}] Slow JSON parsing on ${request.httpUri.summary} tracking-id:${request.trackingId} time:${time}ms data-size:${response.bytes.length} ${tracking.message}"){
  override def toString(): String = getMessage
}

trait ClientResponse {
  def res: Response
  def bytes: Array[Byte]
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
  def parsingTime: Option[Long] = None
}

class ClientResponseException(message: String, cause: Throwable) extends Exception(message, cause)


class ClientResponseImpl(val request: Request, val res: Response, airbrake: Provider[AirbrakeNotifier], jsonParser: FastJsonParser) extends ClientResponse with Logging {

  override def toString: String = s"ClientResponse with [status: $status, body: $body]"

  def status: Int = res.status

  lazy val bytes: Array[Byte] = res.ahcResponse.getResponseBodyAsBytes()
  private var _parsingTime: Option[Long] = None
  override def parsingTime = _parsingTime

  lazy val ahcResponse = res.ahcResponse

  // the following is copied from play.api.libs.ws.WS
  // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
  // explicitly set, while Plays default encoding is UTF-8.  So, use UTF-8 if charset is not explicitly
  // set and content type is not text/*, otherwise default to ISO-8859-1
  lazy val charset = {
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    Option(AsyncHttpProviderUtils.parseCharset(contentType)).getOrElse {
      if (contentType.startsWith("text/"))
        AsyncHttpProviderUtils.DEFAULT_CHARSET
      else
        "utf-8"
    }
  }

  lazy val body: String = {
    val startTime = System.currentTimeMillis
    val str = new String(bytes, charset)
    //if the parser already worked on json or xml we don't recalculate the time as its more likely that the request for the body is for logging now
    if (_parsingTime.isEmpty) _parsingTime = Some(System.currentTimeMillis - startTime)
    str
  }

  lazy val json: JsValue = {
    val url = request.httpUri.url
    //todo: this list should be taken from some config or some smarter mechanizem then that
    val trackTimeThreshold = if(
        url.contains("/getEContacts") ||
        url.contains("/getContacts") ||
        url.contains("/internal/shoebox/database/getIndexable") ||
        url.contains("/internal/shoebox/database/getUriIdsInCollection") ||
        url.contains("graph.facebook.com/")) {
      5000//ms
    } else {
      200//ms
    }
    try {
      val (json, time, tracking) = jsonParser.parse(bytes, trackTimeThreshold)
      _parsingTime = Some(time)
      tracking foreach { info =>
        val exception = request.tracer.withCause(SlowJsonParsingException(request, this, time, info))
        airbrake.get.notify(
          AirbrakeError.outgoing(
            request = request.req,
            response = Some(res),
            exception = exception
          )
        )
      }
      json
    } catch {
      case e: Throwable =>
        log.error(s"bad res: $body")
        throw new ClientResponseException(s"can't parse json on request ${request.httpUri} with charset [$charset], orig type is ${ahcResponse.getContentType}: $body ", e)
    }
  }

  def xml: NodeSeq = {
    try {
      val startTime = System.currentTimeMillis
      val xml = res.xml
      _parsingTime = Some(System.currentTimeMillis - startTime)
      xml
    } catch {
      case e: Throwable =>
        log.error(s"bad res: $body")
        throw e
    }
  }
}
