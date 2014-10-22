package com.keepit.common.concurrent

import java.util.concurrent.Executors

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class WatchableExecutionContext extends ScalaExecutionContext {
  private[this] val internalContext: ScalaExecutionContext = scala.concurrent.ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))
  private[this] val lock = new Object()
  private[this] var addedAnything = false
  private[this] var counter: Int = 0
  private[this] var maxExeutionCount: Int = 0

  override def toString(): String = lock.synchronized { s"WatchableExecutionContext with counter = $counter and maxExeutionCount = $maxExeutionCount" }

  def execute(runnable: Runnable): Unit = lock.synchronized {
    counter += 1
    maxExeutionCount += 1
    addedAnything = true
    val wrapper = new Runnable {
      override def run(): Unit = {
        runnable.run()
        lock.synchronized {
          counter -= 1
          if (counter < 0) throw new Exception(s"Counter should never be less then zero")
          if (counter == 0) lock.notifyAll()
        }
      }
    }
    internalContext.execute(wrapper)
  }

  def drain(waitTime: Long = 1000): Int = {
    lock.synchronized {
      if (!addedAnything || (addedAnything && counter > 0)) {
        lock.wait(waitTime)
      }
    }
    maxExeutionCount
  }

  def reportFailure(t: Throwable): Unit = internalContext.reportFailure(t)

  override def prepare(): ScalaExecutionContext = {
    internalContext.prepare()
    this
  }

}
