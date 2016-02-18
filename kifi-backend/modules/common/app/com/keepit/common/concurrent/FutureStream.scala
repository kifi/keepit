package com.keepit.common.concurrent

import scala.concurrent.{ Future, ExecutionContext => ScalaExecutionContext }

class TaskStream[I, T](items: Iterable[I], fn: I => Future[T])(implicit exc: ScalaExecutionContext) {
  private val taskStream = items.toStream.map(i => Task(i, fn))

  def head: Future[T] = taskStream.head.run
  def take(n: Int): Future[Seq[T]] = FutureHelpers.foldLeft(taskStream.take(n))(Seq.empty[T]) {
    case (futs, task) => task.run.map(_ +: futs)
  }.map(_.reverse)
}
case class Task[I, T](input: I, fn: I => Future[T]) {
  lazy val run: Future[T] = fn(input)
}
