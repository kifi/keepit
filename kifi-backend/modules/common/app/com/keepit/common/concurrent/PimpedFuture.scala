package com.keepit.common.concurrent


import scala.concurrent.{Future, Await, Promise}

import java.util.concurrent.{TimeUnit, Executor}

import com.google.common.util.concurrent.ListenableFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._

import scala.util.Try

object PimpMyFuture {

  implicit class PimpedFuture[T](fut: Future[T]) {

    def asListenableFuture: ListenableFuture[T] = new ListenableFuture[T]{

      def addListener(listener: Runnable, executor: Executor): Unit = {
        fut.onComplete{ res =>
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

    def marker: Future[Unit] = fut.map{ v => ()}

  }


}

object FutureHelpers {

  def map[A,B](in: Map[A,Future[B]]): Future[Map[A,B]] = {
    val seq = in.map{ case (key, fut) =>
      val p = Promise[(A,B)]()
      fut.onComplete{ t =>
        val withKey : Try[(A,B)] = t.map((key, _))
        p.complete(withKey)
      }
      p.future
    }
    Future.sequence(seq).map(_.toMap)
  }


  // generic (make few assumptions) but a bit more work for clients
  def sequentialExec[T](futures:Seq[() => Future[T]]): Future[Seq[T]] = {
    if (futures.isEmpty) Future.successful(List.empty)
    else {
      futures.head.apply.flatMap { h =>
        sequentialExec(futures.tail) map { t => h +: t }
      }
    }
  }

}

