package com.keepit.common.concurrent

import com.keepit.common.logging.Logging

object ExecutionContext extends Logging {
  val immediate = new scala.concurrent.ExecutionContext {
    override def execute(runnable: Runnable): Unit = {
      try {
        runnable.run()
      } catch {
        case t: Throwable => reportFailure(t)
      }
    }
    override def reportFailure(t: Throwable): Unit = { log.error("retry failure", t) }
    override def prepare(): scala.concurrent.ExecutionContext = this
  }
}
