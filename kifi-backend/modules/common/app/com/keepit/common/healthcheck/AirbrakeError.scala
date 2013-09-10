package com.keepit.common.healthcheck

import java.io._
import java.net._
import scala.xml._

import play.api.mvc._

class DefaultAirbrakeException extends Exception

case class AirbrakeError(
    exception: Throwable = new DefaultAirbrakeException(),
    url: Option[String] = None,
    params: Map[String, Seq[String]] = Map(),
    method: Option[String] = None)

object AirbrakeError {
  def apply(request: Request[_], exception: Throwable): AirbrakeError =
    new AirbrakeError(
          exception = exception,
          url = Some(request.uri),
          params = request.queryString,
          method = Some(request.method))
}
