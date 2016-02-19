package com.keepit.common.concurrent

import java.util.NoSuchElementException

import scala.concurrent.{ Future, ExecutionContext => ScalaExecutionContext }
import com.keepit.common.core.futureExtensionOps

class Task[T](f: () => Future[T]) {
  lazy val run: Future[T] = f()
  def map[S](g: T => S): Task[S] = new Task(() => f().imap(g))
  def flatMap[S](g: T => Future[S])(implicit exc: ScalaExecutionContext): Task[S] = new Task(() => f().flatMap(g))
}

sealed trait SyncList[T] {
  // Actual extraction methods
  def head(implicit exc: ScalaExecutionContext): Task[T] = headOption.map(_.getOrElse(throw new scala.NoSuchElementException("head of empty list")))
  def headOption(implicit exc: ScalaExecutionContext): Task[Option[T]]
  def seq(implicit exc: ScalaExecutionContext): Task[Seq[T]]
  def find(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Option[T]] = dropUntil(pred).headOption
  def exists(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Boolean] = find(pred).map(_.isDefined)
  def foldLeft[A](x0: A)(op: (A, T) => A)(implicit exc: ScalaExecutionContext): Task[A]
  def length(implicit exc: ScalaExecutionContext): Task[Int] = foldLeft(0) { case (acc, _) => acc + 1 }
  def count(pred: T => Boolean)(implicit exc: ScalaExecutionContext): Task[Int] = filter(pred).length

  // Mutation methods
  def take(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T]
  def drop(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T]
  def tail(implicit exc: ScalaExecutionContext): SyncList[T]
  def takeWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def dropWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def dropUntil(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = dropWhile(v => !pred(v))
  def filter(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T]
  def map[S](fn: T => S)(implicit exc: ScalaExecutionContext): SyncList[S]
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
    def headOption(implicit exc: ScalaExecutionContext): Task[Option[T]] = new Task(() => Future.successful(None))
    def seq(implicit exc: ScalaExecutionContext): Task[Seq[T]] = new Task(() => Future.successful(Seq.empty))
    def foldLeft[A](x0: A)(op: (A, T) => A)(implicit exc: ScalaExecutionContext): Task[A] = new Task(() => Future.successful(x0))

    def take(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def drop(n: Int)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def tail(implicit exc: ScalaExecutionContext): SyncList[T] = throw new UnsupportedOperationException("tail of empty list")
    def takeWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def dropWhile(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def filter(pred: T => Boolean)(implicit exc: ScalaExecutionContext): SyncList[T] = empty
    def map[S](fn: T => S)(implicit exc: ScalaExecutionContext): SyncList[S] = empty
  }

  private case class Node[T](task: Task[(Option[T], SyncList[T])]) extends SyncList[T] {
    def headOption(implicit exc: ScalaExecutionContext): Task[Option[T]] = task.flatMap {
      case (Some(v), next) => Future.successful(Some(v))
      case (None, next) => next.headOption.run
    }
    def seq(implicit exc: ScalaExecutionContext) = task.flatMap {
      case (Some(v), next) => next.seq.run.imap(v +: _)
      case (None, next) => next.seq.run
    }
    def foldLeft[A](x0: A)(op: (A, T) => A)(implicit exc: ScalaExecutionContext): Task[A] = task.flatMap {
      case (Some(v), next) => next.foldLeft(op(x0, v))(op).run
      case (None, next) => next.foldLeft(x0)(op).run
    }

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

