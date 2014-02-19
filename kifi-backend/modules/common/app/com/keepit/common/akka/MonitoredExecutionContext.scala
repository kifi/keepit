package com.keepit.common.akka


import scala.concurrent.ExecutionContext
import scala.collection.mutable.SynchronizedQueue

import java.util.concurrent.atomic.AtomicInteger


class MonitoredExecutionContext(base: ExecutionContext) extends ExecutionContext {


  protected val waiting: AtomicInteger = new AtomicInteger()
  protected val running: AtomicInteger = new AtomicInteger()


  def waitingCount : Int = waiting.get()
  def runningCount : Int = running.get()

  def execute(runnable: Runnable): Unit = {
    waiting.incrementAndGet()
    val wrapped = new Runnable {
      def run() = {
        waiting.decrementAndGet()
        running.incrementAndGet()
        try runnable.run()
        finally running.decrementAndGet()
      }
    }
    base.execute(wrapped)
  }
  def reportFailure(t: Throwable): Unit = base.reportFailure(t)

}



class ThrottledExecutionContext(base: ExecutionContext, maxTasks: Int) extends MonitoredExecutionContext(base) {

  val queue = new SynchronizedQueue[Runnable]()

  private val submitted: AtomicInteger = new AtomicInteger()

  private def submitOne(): Unit = synchronized {
    if (submitted.get() < maxTasks && queue.length>0) {
      submitted.incrementAndGet()
      base.execute(wrap(queue.dequeue()))
    }
  }

  private def wrap(runnable: Runnable): Runnable = new Runnable {
    def run() = {
      waiting.decrementAndGet()
      running.incrementAndGet()
      try runnable.run()
      finally {
        running.decrementAndGet()
        submitted.decrementAndGet()
        submitOne()
      }
    }
  }

  override def execute(runnable: Runnable): Unit = synchronized {
    queue.enqueue(runnable)
    waiting.incrementAndGet()
    submitOne()
  }

}

