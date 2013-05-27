package com.keepit.common.akka

import com.keepit.common.performance._
import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck

class MonitoredAwait @Inject() (healthcheckPlugin: HealthcheckPlugin) {

  def result[T](awaitable: Awaitable[T], atMost: Duration, valueOnFailure: T): T = {
    val caller = Thread.currentThread().getStackTrace()(2)
    val friendlyCaller = s"${caller.getClassName()}.${caller.getMethodName()}:${caller.getLineNumber()}"
    
    val sw = new Stopwatch(s"Await: $friendlyCaller")
    try {
      Await.result(awaitable, atMost)
    } catch {
      case ex: Throwable =>
        healthcheckPlugin.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        valueOnFailure
    } finally {
      sw.stop()
      sw.logTime()
    }
  }
  
  def result[T](awaitable: Awaitable[T], atMost: Duration) = {
    val caller = Thread.currentThread().getStackTrace()(2)
    val friendlyCaller = s"${caller.getClassName()}.${caller.getMethodName()}:${caller.getLineNumber()}"
    
    val sw = new Stopwatch(s"Await: $friendlyCaller")
    try {
      Await.result(awaitable, atMost)
    } finally {
      sw.stop()
      sw.logTime()
    }
  }
}