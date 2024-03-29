package com.keepit.common.concurrent

import java.util.concurrent._

import com.keepit.common.healthcheck.StackTrace
import play.api.Mode

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class WatchableExecutionContext(mode: Mode.Mode) extends ScalaExecutionContext {

  if (mode == Mode.Prod) throw new IllegalStateException(s"Should not run in production!")

  @volatile private[this] var closed = false
  @volatile private[this] var initiated = false
  private[this] lazy val originExecutor = Executors.newCachedThreadPool()
  private[this] lazy val internalContext: ScalaExecutionContext = {
    initiated = true
    scala.concurrent.ExecutionContext.fromExecutorService(originExecutor)
  }
  private[this] val lock = new Object()
  @volatile private[this] var addedAnything = false
  @volatile private[this] var counter: Int = 0
  @volatile private[this] var maxExecutionCount: Int = 0

  override def toString: String = lock.synchronized { s"WatchableExecutionContext with counter = $counter and maxExecutionCount = $maxExecutionCount" }

  def execute(runnable: Runnable): Unit = lock.synchronized {
    if (closed) {
      println(s"pool is closed!, no point executing runnable")
    } else {
      val trace = new StackTrace()
      counter += 1
      maxExecutionCount += 1
      addedAnything = true
      val wrapper = new Runnable {
        override def run(): Unit = try {
          runnable.run()
          lock.synchronized {
            if (counter <= 1) lock.notifyAll()
            counter -= 1
          }
        } catch {
          case e: Throwable =>
            lock.synchronized { counter -= 1 }
            val t = trace.withCause(e)
            t.printStackTrace()
            throw t
        }
      }
      internalContext.execute(wrapper)
    }
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
    maxExecutionCount
  }

  def kill(): Int = {
    if (initiated) {
      closed = true
      val inProcess = originExecutor.shutdownNow()
      if (!inProcess.isEmpty) {
        println(s"EXECUTOR STOPPED WHILE THERE WHERE ${inProcess.size} RUNNERS IN MID FLIGHT.")
      }
      counter
    } else 0
  }

  def reportFailure(t: Throwable): Unit = internalContext.reportFailure(t)

  override def prepare(): ScalaExecutionContext = {
    internalContext.prepare()
    this
  }

}
