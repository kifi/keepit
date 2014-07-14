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
    foldLeft(items)(()) { case ((), nextItem) => f(nextItem).map { _ => () } }
  }

  def sequentialExecWhile[T](futures: Iterable[Future[T]], predicate: T => Boolean)(implicit ec: ScalaExecutionContext): Future[Unit] = {
    futures.headOption match {
      case None => Future.successful[Unit]()
      case Some(f) => f.flatMap { t =>
        if (predicate(t)) sequentialExecWhile(futures.tail, predicate)
        else Future.successful[Unit]()
      }
    }
  }

  def foldLeft[I, T](items: Iterable[I], promisedResult: Promise[T] = Promise[T]())(accumulator: T)(fMap: (T, I) => Future[T])(implicit ec: ScalaExecutionContext): Future[T] = {
    if (items.isEmpty) { promisedResult.success(accumulator) }
    else fMap(accumulator, items.head).onComplete {
      case Success(updatedAccumulator) => foldLeft(items.tail, promisedResult)(updatedAccumulator)(fMap)
      case Failure(ex) => promisedResult.failure(ex)
    }
    promisedResult.future
  }
}

