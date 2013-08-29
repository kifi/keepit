package com.keepit.common.healthcheck

import java.io._
import java.net._
import scala.xml._

case class AirbrakeError(exception: Throwable)
