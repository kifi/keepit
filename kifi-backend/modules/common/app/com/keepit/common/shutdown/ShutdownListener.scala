package com.keepit.common.shutdown

trait ShutdownListener {
  def shutdown(): Unit
}
