package com.keepit.common.akka

import scala.concurrent.{ExecutionContextExecutor, ExecutionContext}
import play.api.libs.concurrent.Akka
import play.api.Play.current
import java.util.concurrent.Executor
import play.api.Play.current
import play.api.Application
import com.keepit.FortyTwoGlobal
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}

object SlowRunningExecutionContext {
  implicit val ec: ExecutionContext = Akka.system.dispatchers.lookup("slow-running-execution-context")
}
