package com.keepit.common.akka

import scala.concurrent._
import java.util.concurrent.atomic.AtomicReference

object CancelableFuture {
  /*
   * Create a future that can be cancelled, from a blocking code block
   *
   * Example usage:
   *   val (f, cancel) = CancellableFuture(Thread.sleep((Math.random*2000).toInt tap println))
   *   Thread.sleep(2000)
   *   val wasCancelled = cancel()
   *   println("wasCancelled: " + wasCancelled)
   *   f.onFailure { case ex: Throwable => println("failed: " + ex.getClass) }
   *   f.onSuccess { case i => println("success!" + i) }
   */
  def apply[T](fun: => T)(implicit ex: ExecutionContext): (Future[T], () => Boolean) = {
    val promise = Promise[T]()
    val future = promise.future
    val threadRef = new AtomicReference[Thread](null)
    promise tryCompleteWith SafeFuture {
      val t = Thread.currentThread
      threadRef.synchronized { threadRef.set(t) }
      try fun finally { threadRef.synchronized(threadRef.set(null)) }
    }

    (future, () => {
      threadRef.synchronized { Option(threadRef getAndSet null) foreach { _.interrupt() } }
      promise.tryFailure(new CancellationException)
    })
  }
}
