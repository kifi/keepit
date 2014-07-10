package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import scala.util.Success
import scala.util.Failure

object RetryFuture extends Logging {

  private val always: PartialFunction[Throwable, Boolean] = { case t: Throwable => true }

  def apply[T](attempts: Int, resolve: PartialFunction[Throwable, Boolean] = always)(f: => Future[T]): Future[T] = {
    val p = Promise[T]()
    var attempted = 0

    def handler(result: Try[T]): Unit = {
      try {
        result match {
          case Success(r) => p.success(r)
          case Failure(t) =>
            attempted += 1
            if (attempted < attempts && resolve.isDefinedAt(t) && resolve(t)) {
              f.onComplete { handler(_) }(ExecutionContext.immediate) // run the handler immediately in the future completing thread
            } else {
              p.failure(t)
            }
        }
      } catch {
        case t: Throwable => p.failure(t)
      }
    }

    f.onComplete { handler(_) }(ExecutionContext.immediate) // run the handler immediately in the future completing thread

    p.future
  }
}
