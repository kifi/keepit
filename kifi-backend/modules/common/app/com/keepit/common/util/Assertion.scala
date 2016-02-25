package com.keepit.common.util

import scala.concurrent.Future

object Assertion {
  def predicate(pred: Boolean)(fail: Throwable): Future[Unit] = if (pred) Future.successful(()) else Future.failed(fail)
  def find[T](vs: Traversable[T])(pred: T => Boolean)(fail: Throwable): Future[T] = vs.find(pred) match {
    case Some(v) => Future.successful(v)
    case None => Future.failed(fail)
  }
}
