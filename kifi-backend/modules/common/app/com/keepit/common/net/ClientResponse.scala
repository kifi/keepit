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

import com.keepit.common.logging.Logging
import com.keepit.common._
import com.keepit.common.strings._
import com.keepit.common.logging.{Logging, AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError, StackTrace}
import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.controller.CommonHeaders
import com.keepit.common.zookeeper.ServiceDiscovery

import scala.xml._

import org.apache.commons.lang3.RandomStringUtils
import com.ning.http.util.AsyncHttpProviderUtils


case class SlowJsonParsingException(request: Request, response: ClientResponse, tracking: JsonParserTracking)
    extends Exception(s"[${request.httpUri.service}] Slow JSON parsing on ${request.httpUri.summary} tracking-id:${request.trackingId} time:${tracking.time}ms data-size:${response.bytes.length} ${tracking.message}"){
  override def toString(): String = getMessage
}

trait ClientResponse {
  def res: Response
  def bytes: Array[Byte]
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
}

class ClientResponseImpl(val request: Request, val res: Response, airbrake: Provider[AirbrakeNotifier], jsonParser: FastJsonParser) extends ClientResponse with Logging {

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
    val trackTime = !request.httpUri.url.contains("/internal/shoebox/database/getIndexable")
    try {
      val (json, tracking) = jsonParser.parse(bytes, trackTime)

      tracking foreach { info =>
        val exception = request.tracer.withCause(SlowJsonParsingException(request, this, info))
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
        log.error(s"bad res: $body")
        throw e
    }
  }

  def xml: NodeSeq = {
    try {
      res.xml
    } catch {
      case e: Throwable =>
        log.error(s"bad res: $body")
        throw e
    }
  }
}

