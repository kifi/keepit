package com.keepit.common.concurrent

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor, Executors }

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class WatchableExecutionContext extends ScalaExecutionContext {
  @volatile private[this] var closed = false
  @volatile private[this] var initiated = false
  private[this] lazy val originExecutor = new ThreadPoolExecutor(0, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable]())
  private[this] lazy val internalContext: ScalaExecutionContext = {
    initiated = true
    scala.concurrent.ExecutionContext.fromExecutorService(originExecutor)
  }
  private[this] val lock = new Object()
  @volatile private[this] var addedAnything = false
  @volatile private[this] var counter: Int = 0
  @volatile private[this] var maxExeutionCount: Int = 0

  override def toString(): String = lock.synchronized { s"WatchableExecutionContext with counter = $counter and maxExeutionCount = $maxExeutionCount" }

  def execute(runnable: Runnable): Unit = lock.synchronized {
    if (closed) throw new Exception(s"pool is closed, no point executing $runnable")
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
    if (!addedAnything || (addedAnything && counter > 0)) {
      lock.synchronized {
        var pass = 0
        while ((!addedAnything || (addedAnything && counter > 0)) && pass < 2) {
          lock.wait(waitTime / 2)
          pass += 1
        }
      }
    }
    maxExeutionCount
  }

  def kill(): Int = {
    if (initiated) {
      closed = true
      val inProcess = originExecutor.shutdownNow()
      if (!inProcess.isEmpty) {
        println(s"EXECUTOR STOPPED WHILE THERE WHERE ${inProcess.size} RUNNERS IN MID FLIGHT.")
      }
      maxExeutionCount
    } else 0
  }

  def reportFailure(t: Throwable): Unit = internalContext.reportFailure(t)

  override def prepare(): ScalaExecutionContext = {
    internalContext.prepare()
    this
  }

}
