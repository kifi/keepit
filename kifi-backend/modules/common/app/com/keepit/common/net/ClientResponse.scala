package com.keepit.common.net

import com.google.inject.Provider

import com.keepit.common.controller.CommonHeaders
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }

import com.ning.http.util.AsyncHttpProviderUtils.parseCharset

import java.io.{ FileOutputStream, File }
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.{ UTF_8, ISO_8859_1 }

import org.apache.commons.io.IOUtils

import play.api.libs.json._
import play.api.libs.ws._
import play.mvc.Http.Status

import scala.util.Try
import scala.xml._

case class SlowJsonParsingException(request: Request, response: ClientResponse, time: Long, tracking: JsonParserTrackingErrorMessage)
    extends Exception(s"[${request.httpUri.service}] Slow JSON parsing on ${request.httpUri.summary} tracking-id:${request.trackingId} time:${time}ms data-size:${response.bytes.length} ${tracking.message}") {
  override def toString(): String = getMessage
}

trait ClientResponse {
  def res: Response
  def request: Request
  def isUp: Boolean
  def bytes: Array[Byte]
  def body: String
  def json: JsValue
  def xml: NodeSeq
  def status: Int
  def parsingTime: Option[Long] = None
}

class ClientResponseException(message: String, cause: Throwable) extends Exception(message, cause)

class ClientResponseImpl(val request: Request, val res: Response, airbrake: Provider[AirbrakeNotifier], jsonParser: FastJsonParser, maxJsonParseTime: Int) extends ClientResponse with Logging {

  override def toString: String = s"ClientResponse with [status: $status, body: $body]"

  lazy val status: Int = res.status

  lazy val bytes: Array[Byte] = res.ahcResponse.getResponseBodyAsBytes()
  private var _parsingTime: Option[Long] = None
  override def parsingTime = _parsingTime

  /**
   * if the header is NOT there then the remote service does not support it and so we'll assume the service is UP
   */
  def isUp = res.header(CommonHeaders.IsUP).map(_ != "N").getOrElse(true)

  lazy val ahcResponse = res.ahcResponse

  // the following is copied from play.api.libs.ws.WS
  // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
  // explicitly set, while Play's default encoding is UTF-8. So, use UTF-8 if charset is not explicitly
  // set and content type is not text/*, otherwise default to ISO-8859-1
  lazy val charset = {
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    Option(parseCharset(contentType)).map(Charset.forName).getOrElse {
      if (contentType.startsWith("text/"))
        ISO_8859_1
      else
        UTF_8
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
    try {
      val (json, time, tracking) = jsonParser.parse(bytes, maxJsonParseTime)
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
        //The file name is constant, don't change it. We don't want repeating bad jsons to fill up our disk. As a result we store to disk only the latest bad json.
        //Yes, this is not thread safe, but we can live with it.
        val file: File = new File("bad-json.json")
        val output = new FileOutputStream(file)
        try {
          IOUtils.write(bytes, output)
        } finally {
          Try(output.close())
        }
        throw new ClientResponseException(s"can't parse json on request ${request.httpUri} with charset [$charset], orig type is ${ahcResponse.getContentType} bytes are written to ${file.getAbsolutePath()}: $body ", e)
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
