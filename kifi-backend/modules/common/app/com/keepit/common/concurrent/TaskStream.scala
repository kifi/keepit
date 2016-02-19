package com.keepit.common.concurrent

import scala.concurrent.{ Future, ExecutionContext => ScalaExecutionContext }
import com.keepit.common.core.futureExtensionOps

class TaskStream[I, T](tasks: Stream[TaskStream.Task[I, T]])(implicit exc: ScalaExecutionContext) {
  def head: Future[T] = tasks.head.run
  def take(n: Int) = new TaskStream(tasks.take(n))
  def drop(n: Int) = new TaskStream(tasks.drop(n))

  def seq: Future[Seq[T]] = FutureHelpers.foldLeft(tasks)(Seq.empty[T]) {
    case (acc, t) => t.run.map(_ +: acc)
  }.imap(_.reverse)

  def parseq: Future[Seq[T]] = Future.sequence(tasks.map(_.run))

  def foldLeft[B](z: B)(op: (B, T) => B): Future[B] = FutureHelpers.foldLeft(tasks)(z) {
    case (acc, t) => t.run.map(v => op(acc, v))
  }
  def foldLeftUntil[B](z: B)(op: (B, T) => (B, Boolean)): Future[B] = FutureHelpers.foldLeftUntil(tasks)(z) {
    case (acc, t) => t.run.map(v => op(acc, v))
  }
}

object TaskStream {
  case class Task[I, T](input: I, fn: I => Future[T]) {
    lazy val run: Future[T] = fn(input)
  }

  def apply[I, T](inputs: Iterable[I])(fn: I => Future[T])(implicit exc: ScalaExecutionContext): TaskStream[I, T] = new TaskStream(inputs.toStream.map(i => Task(i, fn)))
}
