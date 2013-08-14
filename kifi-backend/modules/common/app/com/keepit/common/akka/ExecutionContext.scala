package com.keepit.common.akka

import scala.concurrent.{Future, ExecutionContextExecutor, ExecutionContext}
import play.api.libs.concurrent.Akka
import play.api.Play.current
import java.util.concurrent.Executor
import play.api.Play.current
import play.api.{Logger, Application}
import com.keepit.FortyTwoGlobal
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}

object SlowRunningExecutionContext {
  implicit val ec: ExecutionContext = Akka.system.dispatchers.lookup("slow-running-execution-context")
}

object MonitoredExecutionContext {
  def monitoredExecutionContext(ec: ExecutionContext) = new ExecutionContext {
    private val proxy: ExecutionContext = ec
    def reportFailure(t: Throwable): Unit = reporter(t)
    def execute(runnable: Runnable): Unit = proxy.execute(new DDD(runnable))
  }

  implicit val defaultContext = monitoredExecutionContext(play.api.libs.concurrent.Execution.defaultContext)
  lazy val slowRunningContext = monitoredExecutionContext(Akka.system.dispatchers.lookup("slow-running-execution-context"))
  lazy val realtimeUserFacingContext = monitoredExecutionContext(Akka.system.dispatchers.lookup("real-time-user-facing-context"))

  private def reporter(cause: Throwable)(implicit app: Application): Unit = {
    cause.printStackTrace() // should always work, to stdout
    try {
      // Needs a running Play application. May fail.
      Logger(getClass).error("[MonitoredExecutionContext] Failure of future", cause)
      app.global.asInstanceOf[FortyTwoGlobal].injector.getInstance(classOf[HealthcheckPlugin])
        .addError(HealthcheckError(Some(cause), None, None, Healthcheck.INTERNAL, Some(s"[MonitoredExecutionContext]: ${cause.getMessage}")))
    } catch {
      case _: Throwable =>
    }
  }
}

