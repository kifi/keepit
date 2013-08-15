package com.keepit.common.akka

import org.jboss.netty.util.{TimerTask, HashedWheelTimer}
import java.util.concurrent.{TimeoutException, TimeUnit}
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration.Duration
import org.jboss.netty.util.Timeout
import com.keepit.common.kestrelCombinator

object TimeoutFuture {
  def apply[T](future: Future[T], onCancel: => Unit = Unit)(implicit ec: ExecutionContext, after: Duration): Future[T] = {
    val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
    val promise = Promise[T]()
    val timeout = timer.newTimeout(new TimerTask {
        def run(timeout: Timeout){
          promise.failure(new TimeoutException(s"Future timed out after ${after.toMillis}ms"))
        }
      }, after.toNanos, TimeUnit.NANOSECONDS)
    // does not cancel future, only resolves result in approx. duration! use onCancel to kill it.
    Future.firstCompletedOf(Seq(future, promise.future)).tap(_.onComplete { case result => timeout.cancel(); onCancel })
  }
}
