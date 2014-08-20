package com.keepit.common.concurrent

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future, Await, Promise }

import java.util.concurrent.{ Executor }

import com.google.common.util.concurrent.ListenableFuture

import scala.concurrent.duration._

import scala.util.{ Failure, Success, Try }

object PimpMyFuture {

  implicit class PimpedFuture[T](fut: Future[T]) {

    def asListenableFuture(implicit ec: ScalaExecutionContext): ListenableFuture[T] = new ListenableFuture[T] {

      def addListener(listener: Runnable, executor: Executor): Unit = {
        fut.onComplete { res =>
          executor.execute(listener)
        }
      }

      def cancel(x: Boolean): Boolean = false

      def isCancelled(): Boolean = false

      def isDone(): Boolean = fut.isCompleted

      def get(): T = Await.result(fut, Duration.Inf)

      def get(timeout: Long, unit: TimeUnit): T = Await.result(fut, Duration(timeout, unit))

    }

    def flatten[E](implicit ev: <:<[T, Future[E]], ec: ScalaExecutionContext): Future[E] = {
      fut.flatMap(r => ev(r))
    }

    def marker(implicit ec: ScalaExecutionContext): Future[Unit] = fut.map { v => () }

  }

}

object FutureHelpers {

  def map[A, B](in: Map[A, Future[B]])(implicit ec: ScalaExecutionContext): Future[Map[A, B]] = {
    val seq = in.map {
      case (key, fut) =>
        val p = Promise[(A, B)]()
        fut.onComplete { t =>
          val withKey: Try[(A, B)] = t.map((key, _))
          p.complete(withKey)
        }
        p.future
    }
    Future.sequence(seq).map(_.toMap)
  }

  def sequentialExec[I, T](items: Iterable[I])(f: I => Future[T])(implicit ec: ScalaExecutionContext): Future[Unit] = {
    foldLeftWhile(items)(()) { case ((), nextItem) => f(nextItem).map { _ => () } }
  }

  def foldLeft[I, T](items: Iterable[I], promisedResult: Promise[T] = Promise[T]())(accumulator: T)(fMap: (T, I) => Future[T])(implicit ec: ScalaExecutionContext): Future[T] = {
    foldLeftWhile(items)(accumulator)(fMap)
  }

  private def foldLeftWhile[I, T](items: Iterable[I], promised: Promise[T] = Promise[T]())(acc: T)(fMap: (T, I) => Future[T], pred: Option[(I) => Boolean] = None)(implicit ec: ScalaExecutionContext): Future[T] = {
    if (items.isEmpty) { promised.success(acc) }
    else {
      val item = items.head
      fMap(acc, item).onComplete {
        case Success(updatedAcc) =>
          if (pred.forall(_.apply(item))) foldLeftWhile(items.tail, promised)(updatedAcc)(fMap, pred)
          else promised.success(updatedAcc) // short-circuit
        case Failure(ex) => promised.failure(ex)
      }
    }
    promised.future
  }

  def whilef(f: => Future[Boolean], p: Promise[Unit] = Promise[Unit]())(body: => Unit)(implicit ec: ScalaExecutionContext): Future[Unit] = {
    f.onComplete {
      case Success(true) => {
        try {
          body
          whilef(f, p)(body)
        } catch {
          case t: Throwable => p.failure(t)
        }
      }
      case Success(false) => p.success()
      case Failure(ex) => p.failure(ex)
    }
    p.future
  }

}

