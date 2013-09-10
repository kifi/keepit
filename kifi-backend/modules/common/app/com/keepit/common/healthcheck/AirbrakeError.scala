package com.keepit.common.healthcheck

import java.io._
import java.net._
import scala.xml._

class DefaultAirbrakeException extends Exception

case class AirbrakeError(
    exception: Throwable = new DefaultAirbrakeException(),
    url: Option[String] = None,
    params: Map[String,List[String]] = Map(),
    method: Option[String] = None)
