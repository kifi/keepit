package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId

import java.io._
import java.net._
import scala.xml._

import play.api.mvc._
import play.api.libs.ws.WS.WSRequestHolder

class DefaultAirbrakeException extends Exception

case class AirbrakeError(
    exception: Throwable = new DefaultAirbrakeException(),
    message: Option[String] = None,
    url: Option[String] = None,
    params: Map[String, Seq[String]] = Map(),
    method: Option[String] = None,
    headers: Map[String, Seq[String]] = Map(),
    id: ExternalId[AirbrakeError] = ExternalId())

object AirbrakeError {
  val MaxMessageSize = 10 * 1024 //10KB
  def incoming(request: RequestHeader, exception: Throwable = new DefaultAirbrakeException(), message: String = ""): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          message = if (message.trim.isEmpty) None else Some(message.take(MaxMessageSize)),
          url = Some(request.uri.take(MaxMessageSize)),
          params = request.queryString,
          method = Some(request.method),
          headers = request.headers.toMap)

  def outgoing(request: WSRequestHolder, exception: Throwable = new DefaultAirbrakeException(), message: String = ""): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          message = if (message.trim.isEmpty) None else Some(message.take(MaxMessageSize)),
          url = Some(request.url.take(MaxMessageSize)),
          params = request.queryString,
          headers = request.headers)

  implicit def error(t: Throwable): AirbrakeError = AirbrakeError(t)

}

