package com.keepit.common.util

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core.futureExtensionOps

final class TimedComputation[T](val value: T, val start: Long, val end: Long) {
  def millis = end - start
  def range = (start, end)
}
object TimedComputation {
  def sync[T](fn: => T): TimedComputation[T] = {
    val start = System.currentTimeMillis()
    val v = fn
    val end = System.currentTimeMillis()
    new TimedComputation[T](v, start, end)
  }
  def async[T](fn: => Future[T])(implicit exc: ExecutionContext): Future[TimedComputation[T]] = {
    val start = System.currentTimeMillis()
    fn.imap { v =>
      val end = System.currentTimeMillis()
      new TimedComputation[T](v, start, end)
    }
  }
}
