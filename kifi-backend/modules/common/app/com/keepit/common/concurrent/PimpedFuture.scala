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
  import PimpMyFuture._

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

  def combine[T1, T2](f1: Future[T1], f2: Future[T2]): Future[(T1,T2)] = {
    val p = Promise[(T1,T2)]()
    val masterFuture = Future.sequence(Seq(f1.marker, f2.marker))
    masterFuture.onComplete{ t =>
      val completeTry = t.map{ _ =>
        (Await.result(f1,Duration.Inf), Await.result(f2,Duration.Inf))
      }
      p.complete(completeTry)
    }
    p.future
  }

  def combine[T1, T2, T3](f1: Future[T1], f2: Future[T2], f3: Future[T3]): Future[(T1,T2,T3)] = {
    val p = Promise[(T1,T2,T3)]()
    val masterFuture = Future.sequence(Seq(f1.marker, f2.marker, f3.marker))
    masterFuture.onComplete{ t =>
      val completeTry = t.map{ _ =>
        (Await.result(f1,Duration.Inf), Await.result(f2,Duration.Inf), Await.result(f3,Duration.Inf))
      }
      p.complete(completeTry)
    }
    p.future
  }

  def combine[T1, T2, T3, T4](f1: Future[T1], f2: Future[T2], f3: Future[T3], f4: Future[T4]): Future[(T1,T2,T3,T4)] = {
    val p = Promise[(T1,T2,T3, T4)]()
    val masterFuture = Future.sequence(Seq(f1.marker, f2.marker, f3.marker, f4.marker))
    masterFuture.onComplete{ t =>
      val completeTry = t.map{ _ =>
        (Await.result(f1,Duration.Inf), Await.result(f2,Duration.Inf), Await.result(f3,Duration.Inf), Await.result(f4,Duration.Inf))
      }
      p.complete(completeTry)
    }
    p.future
  }

  def combine[T1, T2, T3, T4, T5](f1: Future[T1], f2: Future[T2], f3: Future[T3], f4: Future[T4], f5: Future[T5]): Future[(T1,T2,T3,T4,T5)] = {
    val p = Promise[(T1,T2,T3, T4, T5)]()
    val masterFuture = Future.sequence(Seq(f1.marker, f2.marker, f3.marker, f4.marker, f5.marker))
    masterFuture.onComplete{ t =>
      val completeTry = t.map{ _ =>
        (Await.result(f1,Duration.Inf), Await.result(f2,Duration.Inf), Await.result(f3,Duration.Inf), Await.result(f4,Duration.Inf), Await.result(f5,Duration.Inf))
      }
      p.complete(completeTry)
    }
    p.future
  }


}

