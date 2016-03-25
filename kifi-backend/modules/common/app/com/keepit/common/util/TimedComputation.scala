package com.keepit.common.util

import scala.concurrent.{ ExecutionContext, Future }

final class TimedComputation[T](val value: T, val millis: Long)
object TimedComputation {
  def sync[T](fn: => T): TimedComputation[T] = {
    val start = System.currentTimeMillis()
    val v = fn
    val end = System.currentTimeMillis()
    new TimedComputation[T](v, end - start)
  }
  def async[T](fn: => Future[T])(implicit exc: ExecutionContext): Future[TimedComputation[T]] = {
    val start = System.currentTimeMillis()
    fn.map { v =>
      val end = System.currentTimeMillis()
      new TimedComputation[T](v, end - start)
    }
  }
}
