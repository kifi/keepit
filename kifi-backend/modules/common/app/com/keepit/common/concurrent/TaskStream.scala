package com.keepit.common.concurrent

import scala.concurrent.{ Future, ExecutionContext => ScalaExecutionContext }
import com.keepit.common.core.futureExtensionOps

class TaskStream[I, T](val tasks: Stream[TaskStream.Task[I, Option[T]]])(implicit exc: ScalaExecutionContext) {
  def headOption: Future[Option[T]] = FutureHelpers.collectFirst(tasks)(_.run)
  def head: Future[T] = headOption.imap(_.get)

  def filter(pred: T => Boolean) = new TaskStream(tasks.map(t => t.map(_.filter(pred))))
  def filterNot(pred: T => Boolean) = new TaskStream(tasks.map(t => t.map(_.filterNot(pred))))

  def find(pred: T => Boolean): Future[Option[T]] = filter(pred).headOption

  def collect[S](fn: PartialFunction[T, S]): TaskStream[I, S] = new TaskStream(tasks.map(t => t.map(_.flatMap(fn.lift))))
  def collectWith[S](fn: PartialFunction[T, Future[S]]): TaskStream[I, S] = new TaskStream(tasks.map(t => t.flatMap {
    case Some(v) if fn.isDefinedAt(v) => fn(v).map(Some(_))
    case _ => Future.successful(None)
  }))

  def take(n: Int): Future[Seq[T]] = FutureHelpers.foldLeftUntil(tasks)(Seq.empty[T]) {
    case (acc, t) => t.run.imap {
      case Some(v) => (v +: acc, acc.length + 1 == n)
      case None => (acc, acc.length == n)
    }
  }.imap(_.reverse)

  def seq: Future[Seq[T]] = take(Int.MaxValue)

  def foldLeft[B](z: B)(op: (B, T) => B): Future[B] = FutureHelpers.foldLeft(tasks)(z) {
    case (acc, t) => t.run.map {
      case Some(v) => op(acc, v)
      case None => acc
    }
  }
  def foldLeftUntil[B](z: B)(op: (B, T) => (B, Boolean)): Future[B] = FutureHelpers.foldLeftUntil(tasks)(z) {
    case (acc, t) => t.run.map {
      case Some(v) => op(acc, v)
      case None => (acc, true)
    }
  }
}

object TaskStream {
  case class Task[I, T](input: I, f: I => Future[T]) {
    lazy val run: Future[T] = f(input)
    def map[S](g: T => S): Task[I, S] = Task(input, x => f(x).imap(g))
    def flatMap[S](g: T => Future[S])(implicit exc: ScalaExecutionContext): Task[I, S] = Task(input, x => f(x).flatMap(g))
  }
  private def trivialTask[I](input: I): Task[I, Option[I]] = Task(input, i => Future.successful(Some(i)))

  def apply[I, T](inputs: Iterable[I])(fn: I => Future[T])(implicit exc: ScalaExecutionContext): TaskStream[I, T] = {
    def alwaysSome(i: I): Future[Option[T]] = fn(i).imap(v => Some(v))
    new TaskStream(inputs.toStream.map(i => Task(i, alwaysSome)))
  }
  def iterate[I](x0: I)(fn: I => Future[I])(implicit exc: ScalaExecutionContext): TaskStream[I, I] = {
    new TaskStream(Stream.iterate(trivialTask(x0))(t => t.flatMap {
      case Some(v) => fn(v).imap(Some(_))
      case None => Future.successful(None)
    }))
  }
}
