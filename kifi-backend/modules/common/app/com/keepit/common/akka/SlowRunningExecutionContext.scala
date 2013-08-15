package com.keepit.common.akka

import scala.concurrent.ExecutionContext
import play.api.libs.concurrent.Akka
import play.api.Play.current

object SlowRunningExecutionContext {
  implicit val ec: ExecutionContext = Akka.system.dispatchers.lookup("slow-running-execution-context")
}
