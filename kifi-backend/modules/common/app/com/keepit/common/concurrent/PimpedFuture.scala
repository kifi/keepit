package com.keepit.common.concurrent


import scala.concurrent.{Future, Await}

import java.util.concurrent.{TimeUnit, Executor}

import com.google.common.util.concurrent.ListenableFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._


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

  }

}



