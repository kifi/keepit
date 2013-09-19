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
  def apply(request: Request[_], exception: Throwable): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          url = Some(request.uri),
          params = request.queryString,
          method = Some(request.method),
          headers = request.headers.toMap)

  def apply(request: Request[_], exception: Throwable, message: String): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          message = Some(message),
          url = Some(request.uri),
          params = request.queryString,
          method = Some(request.method),
          headers = request.headers.toMap)
}
