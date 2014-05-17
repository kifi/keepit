package com.keepit.common.akka

package com.keepit.common.akka.ThrottledExecutionContext

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.annotation.tailrec

/**
 * From https://gist.github.com/kevinwright/4949162
 */

object ThrottledExecutionContext {
  def apply(maxConcurrents: Int)(implicit context: ExecutionContext): ExecutionContext = {

    require(maxConcurrents > 0, s"ThrottledExecutionContext.maxConcurrents must be greater than 0 but was $maxConcurrents")

    new ConcurrentLinkedQueue[Runnable] with Runnable with ExecutionContext {
      private final val on = new AtomicInteger(0)

      @tailrec private def seizeSlot(): Boolean = {
        val n = on.get
        n < maxConcurrents && (on.compareAndSet(n, n+1) || seizeSlot())
      }

      private def releaseSlot(): Unit = on.decrementAndGet()

      override def add(task: Runnable): Boolean = {
        val r = super.add(task)
        attach()
        r
      }

      final def run(): Unit = try {
        poll() match {
          case null => ()
          case some => try some.run() catch { case NonFatal(t) => context reportFailure t }
        }
      } finally {
        releaseSlot()
        attach()
      }

      final def attach(): Unit =
        if(!isEmpty && seizeSlot()) {
          try context.execute(this) catch { case t: Throwable => releaseSlot(); throw t }
        }

      override final def execute(task: Runnable): Unit = add(task)
      override final def reportFailure(t: Throwable): Unit = context reportFailure t
    }
  }
}
