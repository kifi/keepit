package com.keepit.common.akka

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifierStatic }
import play.api.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.{ CanAwait, ExecutionContext, Future }
import scala.util.Try

class SafeFuture[+T](future: Future[T], name: Option[String] = None)(implicit executor: ExecutionContext) extends Future[T] {

  future match {
    case _: SafeFuture[_] =>
    case dangerousFuture =>
      dangerousFuture.onFailure {
        case cause: Throwable =>
          try {
            // Needs a running Play application. May fail.
            Logger(getClass).error(s"[SafeFuture] Failure of future [${name.getOrElse("")}]", cause)
            AirbrakeNotifierStatic.notify(AirbrakeError(cause, Some(s"SafeFuture[${name.getOrElse("")}]")))

          } catch {
            case _: Throwable => // tried our best.
              System.err.println(s"SafeFuture exception: ${cause.toString}")
              cause.printStackTrace() // should always work, to stdout
          }
      }
  }

  // Just a wrapper around Future. Wakka Wakka.
  def onComplete[U](func: (Try[T]) => U)(implicit executor: ExecutionContext): Unit = future.onComplete(func)
  def isCompleted: Boolean = future.isCompleted
  def value: Option[Try[T]] = future.value
  def ready(atMost: Duration)(implicit permit: CanAwait): this.type = { future.ready(atMost); this }
  def result(atMost: Duration)(implicit permit: CanAwait): T = future.result(atMost)

}

object SafeFuture {
  def apply[T](func: => T)(implicit executor: ExecutionContext) = new SafeFuture(Future { func })
  def apply[T](name: String)(func: => T)(implicit executor: ExecutionContext) = new SafeFuture(Future { func }, Some(name))

  def swallow(f: => Future[Unit])(implicit executor: ExecutionContext) = new SafeFuture(FutureHelpers.safely(f))
}

