package com.keepit.common.util

import java.util.concurrent.atomic.AtomicInteger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

trait RecurringTaskManager {

  def doTask(): Unit
  def onError(e: Throwable): Unit

  private[this] val state = new AtomicInteger(0) // 0: idle, 1: running, 2-or-greater: pending

  def request(): Unit = {
    if (state.getAndAdd(2) == 0) {
      // the state was 0, we need to start the task
      Future {
        try {
          while (state.decrementAndGet() > 0) {
            // the state was 2-or-greater, there are pending requests, start the task
            // all pending requests are consumed by this
            state.set(1)
            doTask()
          }
        } catch {
          case e: Throwable =>
            state.set(0) // go to the idle state so that the next request will start the task
            onError(e)
        }
      }
    }
  }
}
