package com.keepit.common.concurrent

import play.modules.statsd.api.Statsd

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class MonitoredExecutionContext(underlying: ScalaExecutionContext, samplingRate: Double) extends ScalaExecutionContext {
  private val underlyingName = underlying.getClass.getSimpleName
  private val executeMetric = s"executionContext.$underlyingName.execute"
  override def execute(runnable: Runnable): Unit = {
    val executingAt = System.currentTimeMillis()
    val monitoredRunnable = new Runnable {
      def run(): Unit = {
        val runningAt = System.currentTimeMillis()
        Statsd.timing(executeMetric, runningAt - executingAt, samplingRate)
        runnable.run()
      }
    }
    underlying.execute(monitoredRunnable)
  }
  override def reportFailure(t: Throwable): Unit = underlying.reportFailure(t)
  override def prepare(): scala.concurrent.ExecutionContext = {
    val underlyingPrepared = underlying.prepare()
    if (underlying == underlyingPrepared) this
    else new MonitoredExecutionContext(underlyingPrepared, samplingRate)
  }
}

