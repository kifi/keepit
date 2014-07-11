package com.keepit.common.concurrent

import scala.concurrent.{ Future, Await, Promise }

import java.util.concurrent.{ TimeUnit, Executor }

import com.google.common.util.concurrent.ListenableFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._

import scala.util.Try

object PimpMyFuture {

  implicit class PimpedFuture[T](fut: Future[T]) {

    def asListenableFuture: ListenableFuture[T] = new ListenableFuture[T] {

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

    def flatten[E](implicit ev: <:<[T, Future[E]]): Future[E] = {
      fut.flatMap(r => ev(r))
    }

    def marker: Future[Unit] = fut.map { v => () }

  }

}

object FutureHelpers {

  def map[A, B](in: Map[A, Future[B]]): Future[Map[A, B]] = {
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

  def sequentialExec[I, T](items: Iterable[I])(f: I => Future[T]): Future[Unit] = {
    items.headOption match {
      case None => Future.successful[Unit]()
      case Some(item) => f(item).flatMap { h =>
        sequentialExec(items.tail)(f)
      }
    }
  }

  // sequentialExec with short-circuit based on predicate
  def sequentialPartialExec[T](futures: Iterable[Future[T]], predicate: T => Boolean): Future[Unit] = {
    futures.headOption match {
      case None => Future.successful[Unit]()
      case Some(f) => f.flatMap { t =>
        if (predicate(t)) Future.successful[Unit]()
        else sequentialPartialExec(futures.tail, predicate)
      }
    }
  }

}

