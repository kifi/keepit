package com.keepit.common.akka

import scala.concurrent.{ExecutionContext, CanAwait, Awaitable, Future}
import scala.concurrent.duration.Duration
import scala.util.Try
import play.api.{Logger}
import com.keepit.FortyTwoGlobal
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import com.keepit.common.KestrelCombinator
import play.api.Play.current

class SafeFuture[+T](future: Future[T], name: Option[String] = None)(implicit executor: ExecutionContext) extends Future[T] {
  private val safeFuture = future tap { f =>
    f match {
      case _: SafeFuture[_] =>
      case dangerousFuture =>
        dangerousFuture.onFailure {
          case cause: Throwable =>
            cause.printStackTrace() // should always work, to stdout
            try {
              // Needs a running Play application. May fail.
              Logger(getClass).error(s"[SafeFuture] Failure of future${name.map(": " + _).getOrElse("")}", cause)
              val fortyTwoInjector = current.global.asInstanceOf[FortyTwoGlobal].injector
              fortyTwoInjector.getInstance(classOf[HealthcheckPlugin]).addError(
                HealthcheckError(Some(cause), None, None, Healthcheck.INTERNAL, Some(s"[SafeFuture]: ${cause.getMessage}"))
              )
            } catch {
              case _: Throwable => // tried our best.
            }
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
}


