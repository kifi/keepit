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

  def foldLeft[I, T](items: Iterable[I], promisedResult: Promise[T] = Promise[T]())(accumulator: T)(fMap: (T, I) => Future[T])(implicit ec: ScalaExecutionContext): Future[T] = {
    if (items.isEmpty) { promisedResult.success(accumulator) }
    else fMap(accumulator, items.head).onComplete {
      case Success(updatedAccumulator) => foldLeft(items.tail, promisedResult)(updatedAccumulator)(fMap)
      case Failure(ex) => promisedResult.failure(ex)
    }
    promisedResult.future
  }

  def whilef(f: => Future[Boolean], toComplete: Option[Promise[Unit]] = None)(body: => Unit)(implicit ec: ScalaExecutionContext): Future[Unit] = {
    val p = toComplete.getOrElse(Promise[Unit])
    f.onComplete {
      case Success(t) if t => {
        try {
          body
          whilef(f, Some(p))(body)
        } catch {
          case t: Throwable => p.failure(t)
        }
      }
      case Success(f) => p.success()
      case Failure(ex) => p.failure(ex)
    }
    p.future
  }

}

