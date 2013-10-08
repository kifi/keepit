package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId

import java.io._
import java.net._
import scala.xml._

import play.api.mvc._

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
  val MaxStringSize = 1024 * 1024 //1MB
  def apply(request: RequestHeader, exception: Throwable): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          url = Some(request.uri.take(MaxStringSize)),
          params = request.queryString.take(MaxStringSize),
          method = Some(request.method),
          headers = request.headers.toMap)

  def apply(request: RequestHeader, exception: Throwable, message: String): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          message = Some(message.take(MaxStringSize)),
          url = Some(request.uri.take(MaxStringSize)),
          params = request.queryString.take(MaxStringSize),
          method = Some(request.method),
          headers = request.headers.toMap)

  implicit def error(t: Throwable): AirbrakeError = AirbrakeError(t)

}

