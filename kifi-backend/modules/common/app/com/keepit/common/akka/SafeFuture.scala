package com.keepit.common.akka

import scala.concurrent.{ExecutionContext, CanAwait, Awaitable, Future}
import scala.concurrent.duration.Duration
import scala.util.Try
import play.api.{Logger}
import com.keepit.FortyTwoGlobal
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import com.keepit.common.kestrelCombinator
import play.api.Play.current

class SafeFuture[+T](future: Future[T], name: Option[String] = None)(implicit executor: ExecutionContext) extends Future[T] with Awaitable[T] {
  private val safeFuture = future tap (_.onFailure {
    case cause: Throwable =>
      cause.printStackTrace() // should always work, to stdout
      try {
        // Needs a running Play application. May fail.
        Logger(getClass).error(s"[SafeFuture] Failure of future${name.map(": " + _).getOrElse("")}", cause)
        val fortyTwoInjector = current.global.asInstanceOf[FortyTwoGlobal].injector
        fortyTwoInjector.getInstance(classOf[HealthcheckPlugin]).addError(
          HealthcheckError(Some(cause), None, None, Healthcheck.INTERNAL, Some(s"[MonitoredExecutionContext]: ${cause.getMessage}"))
        )
      } catch {
        case _: Throwable => 
      }
  })

  // Just a wrapper around Future with Awaitable. Wakka Wakka.
  override def result(atMost: Duration)(implicit permit: CanAwait): T = future.result(atMost)(permit)
  override def ready(atMost : Duration)(implicit permit: CanAwait): SafeFuture.this.type = future.ready(atMost).asInstanceOf[SafeFuture.this.type]
  override def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = future.onSuccess(pf)
  override def onFailure[U](callback: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit = future.onFailure(callback)
  def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = future.onComplete(func)
  def isCompleted: Boolean = future.isCompleted
  def value: Option[util.Try[T]] = future.value
  override def failed: Future[Throwable] = future.failed
  override def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit = future.foreach(f)
  override def transform[S](s: T => S, f: Throwable => Throwable)(implicit executor: ExecutionContext): Future[S] = future.transform(s, f)
  override def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] = future.map(f)
  override def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = future.flatMap(f)
  override def filter(pred: T => Boolean)(implicit executor: ExecutionContext): Future[T] = future.filter(pred)
  override def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] = future.collect(pf)
  override def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] = future.recover(pf)
  override def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] = future.recoverWith(pf)
  override def zip[U](that: Future[U]): Future[Tuple2[T, U]] = future.zip(that)
  override def fallbackTo[U >: T](that: Future[U]): Future[U] = future.fallbackTo(that)
  override def mapTo[S](implicit tag: reflect.ClassTag[S]): Future[S] = future.mapTo
  override def andThen[U](pf: PartialFunction[util.Try[T], U])(implicit executor: ExecutionContext): Future[T] = future.andThen(pf)
}

object SafeFuture {
  def apply[T](func: => T)(implicit executor: ExecutionContext) = new SafeFuture(Future { func })
  def apply[T](name: String)(func: => T)(implicit executor: ExecutionContext) = new SafeFuture(Future { func }, Some(name))
}


