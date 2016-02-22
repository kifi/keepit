package com.keepit.common.concurrent

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Promise, Future }
import com.keepit.common.core.futureExtensionOps

import scala.util.{ Failure, Success }

class Task[T](f: () => Future[T]) {
  lazy val run: Future[T] = f()
  def map[S](g: T => S) = new Task(() => f().imap(g))
}

sealed trait SyncList[T] {
  // Actual extraction method
  protected def foldLeftUntil[A](x0: A, p: Promise[A])(op: (A, T) => (A, Boolean))(implicit exc: ScalaExecutionContext): Task[A]

  // Derived extraction methods
  def foldLeft[A](x0: A)(op: (A, T) => A)(implicit exc: ScalaExecutionContext): Task[A] = foldLeftUntil[A](x0, Promise()) { case (x, v) => (op(x, v), false) }
  def headOption(implicit exc: ScalaExecutionContext): Task[Option[T]] = foldLeftUntil[Option[T]](None, Promise()) { case (_, v) => (Some(v), true) }
  def head(implicit exc: ScalaExecutionContext): Task[T] = headOption.map(_.getOrElse(throw new scala.NoSuchElementException("head of empty list")))
  def seq(implicit exc: ScalaExecutionContext): Task[Seq[T]] = foldLeft(Seq.empty[T]) { case (acc, v) => v +: acc }.map(_.reverse)
  def find(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Option[T]] = dropUntil(pred).headOption
  def exists(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Boolean] = find(pred).map(_.isDefined)
  def length(implicit exc: ScalaExecutionContext): Task[Int] = foldLeft(0) { case (acc, _) => acc + 1 }
  def count(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Int] = filter(pred).length

  // Mutation methods
  def take(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T]
  def drop(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T]
  def tail(implicit exc: ScalaExecutionContext): SyncList[T]
  def takeWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def dropWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def filter(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def map[S](fn: T => S)(implicit exc: ScalaExecutionContext): SyncList[S]

  // Derived mutation methods
  def dropUntil(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = dropWhile(v => !pred(v))
}
object SyncList {
  def empty[T]: SyncList[T] = End[T]()
  def independent[I, T](items: Iterable[I])(fn: I => Future[T]): SyncList[T] = items.foldRight(empty[T]) {
    case (i, rest) => Node(new Task(() => fn(i).imap(v => Option(v) -> rest)))
  }
  def iterate[T](x0: T)(fn: T => Future[T]): SyncList[T] = Node(new Task(() => fn(x0).imap {
    x1 => (Some(x0), iterate(x1)(fn))
  }))
  def accumulate[T, A](x0: A)(fn: A => Future[(T, A)]): SyncList[T] = Node(new Task(() => fn(x0).imap {
    case (v, x1) => (Some(v), accumulate(x1)(fn))
  }))

  private case class End[T]() extends SyncList[T] {
    override def foldLeftUntil[A](x0: A, p: Promise[A])(op: (A, T) => (A, Boolean))(implicit exc: ScalaExecutionContext): Task[A] = new Task(() => {
      p.success(x0)
      p.future
    })

    def take(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def drop(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def tail(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def takeWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def dropWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def filter(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def map[S](fn: T => S)(implicit exc: ScalaExecutionContext): SyncList[S] = empty
  }

  private case class Node[T](task: Task[(Option[T], SyncList[T])]) extends SyncList[T] {
    override def foldLeftUntil[A](x0: A, p: Promise[A])(op: (A, T) => (A, Boolean))(implicit exc: ScalaExecutionContext): Task[A] = new Task(() => {
      task.run.onComplete {
        case Failure(fail) => p.failure(fail)
        case Success((None, next)) =>
          next.foldLeftUntil(x0, p)(op).run
        case Success((Some(v), next)) =>
          val (x1, done) = op(x0, v)
          if (done) p.success(x1)
          else next.foldLeftUntil(x1, p)(op).run
      }
      p.future
    })

    def take(n: Int)(implicit exc: ScalaExecutionContext) = if (n <= 0) empty else Node(task.map {
      case (firstOpt, next) => (firstOpt, next.take(if (firstOpt.isEmpty) n else n - 1))
    })
    def drop(n: Int)(implicit exc: ScalaExecutionContext) = if (n <= 0) this else Node(task.map {
      case (firstOpt, next) => (None, next.drop(if (firstOpt.isEmpty) n else n - 1))
    })
    def tail(implicit exc: ScalaExecutionContext): SyncList[T] = Node(task.map {
      case (firstOpt, next) => (None, if (firstOpt.isDefined) next else next.tail)
    })
    def takeWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = Node(task.map {
      case (Some(v), next) if !pred(v) => (None, empty)
      case (other, next) => (other, next.takeWhile(pred))
    })
    def dropWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = Node(task.map {
      case (Some(v), next) if !pred(v) => (Some(v), next)
      case (_, next) => (None, next.dropWhile(pred))
    })
    def filter(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = Node(task.map {
      case (firstOpt, next) => (firstOpt.filter(pred), next.filter(pred))
    })
    def map[S](fn: T => S)(implicit exc: ScalaExecutionContext): SyncList[S] = Node(task.map {
      case (firstOpt, next) => (firstOpt.map(fn), next.map(fn))
    })
  }
}

