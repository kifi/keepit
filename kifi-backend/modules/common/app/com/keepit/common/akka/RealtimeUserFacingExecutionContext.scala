package com.keepit.common.akka

import scala.concurrent.ExecutionContext
import play.api.libs.concurrent.Akka
import play.api.Play.current

object RealtimeUserFacingExecutionContext {
  implicit val ec: ExecutionContext = Akka.system.dispatchers.lookup("real-time-user-facing-context")
}
