package com.keepit.common.akka

import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError, HealthcheckPlugin }
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.duration._
import com.google.inject.Inject

class MonitoredAwait @Inject() (airbrake: AirbrakeNotifier, healthcheckPlugin: HealthcheckPlugin) extends com.keepit.macros.MonitoredAwait {

  def onError(tag: String, ex: Throwable, errorMessage: String) = airbrake.notify(AirbrakeError(ex, Some(s"$tag [$errorMessage]")))

  def logTime(tag: String, elapsedTimeNano: Long): Unit = {}

  lazy val logging: MonitoredAwait = new MonitoredAwaitLogging(airbrake, healthcheckPlugin)
}

class MonitoredAwaitLogging(airbrake: AirbrakeNotifier, healthcheckPlugin: HealthcheckPlugin) extends MonitoredAwait(airbrake, healthcheckPlugin) with Logging {

  override def logTime(tag: String, elapsedTimeNano: Long): Unit = {
    log.info(s"$tag elapsed milliseconds: ${(elapsedTimeNano / 1000000d)}")
  }

  override lazy val logging: MonitoredAwait = this
}

