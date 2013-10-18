package com.keepit.common.akka

import com.keepit.common.performance._
import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError, HealthcheckPlugin}

class MonitoredAwait @Inject() (airbrake: AirbrakeNotifier, healthcheckPlugin: HealthcheckPlugin) {

  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String, valueFailureHandler: T): T = {
    val caller = Thread.currentThread().getStackTrace()(2)
    val tag = s"Await: ${caller.getClassName()}.${caller.getMethodName()}:${caller.getLineNumber()}"

    val sw = new Stopwatch(tag)
    try {
      Await.result(awaitable, atMost)
    } catch {
      case ex: Throwable =>
        airbrake.notify(AirbrakeError(ex, Some(s"[$errorMessage]: ${ex.getMessage}")))
        valueFailureHandler
    } finally {
      sw.stop()
      sw.logTime()
    }
  }

  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String) = {
    val caller = Thread.currentThread().getStackTrace()(2)
    val tag = s"Await: ${caller.getClassName()}.${caller.getMethodName()}:${caller.getLineNumber()}"

    val sw = new Stopwatch(tag)
    try {
      Await.result(awaitable, atMost)
    } catch {
      case ex: Throwable =>
        if (healthcheckPlugin.isWarm)
          airbrake.notify(AirbrakeError(ex, Some(s"[$errorMessage]: ${ex.getMessage}")))
        throw ex
    } finally {
      sw.stop()
      sw.logTime()
    }
  }
}
