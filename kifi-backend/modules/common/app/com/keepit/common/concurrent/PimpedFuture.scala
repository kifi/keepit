package com.keepit.common.concurrent

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future, Await, Promise }

import java.util.concurrent.{ Executor }

import com.google.common.util.concurrent.ListenableFuture

import scala.concurrent.duration._

import scala.util.{ Failure, Success, Try }

import com.keepit.common.core._

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
    Future.sequence(seq).imap(_.toMap)
  }

  def sequentialExec[I, T](items: Iterable[I])(f: I => Future[T])(implicit ec: ScalaExecutionContext): Future[Unit] = {
    foldLeft(items)(()) { case ((), nextItem) => f(nextItem).imap { _ => () } }
  }

  private val noopChunkCB: Int => Unit = _ => Unit

  // sequential execute in chunks + callback (optional)
  def chunkySequentialExec[I, T](items: Iterable[I], chunkSize: Int = 10, chunkCB: Int => Unit = noopChunkCB)(f: I => Future[T])(implicit ec: ScalaExecutionContext) = {
    chunkyExec(items.grouped(chunkSize).zipWithIndex, chunkSize)(f, if (chunkCB == noopChunkCB) None else Some(chunkCB))
  }

  private def chunkyExec[I, T](iter: Iterator[(Iterable[I], Int)], chunkSize: Int, promised: Promise[Unit] = Promise[Unit]())(f: I => Future[T], chunkCB: Option[Int => Unit] = None)(implicit ec: ScalaExecutionContext): Future[Unit] = {
    if (iter.isEmpty) promised.success(())
    else {
      val items = iter.next
      sequentialExec(items._1)(f) onComplete {
        case Success(_) =>
          chunkCB.foreach { _.apply(items._2) }
          chunkyExec(iter, chunkSize, promised)(f, chunkCB)
        case Failure(t) => promised.failure(t)
      }
    }
    promised.future
  }

  def foldLeft[I, T](items: Iterable[I])(accumulator: T)(fMap: (T, I) => Future[T])(implicit ec: ScalaExecutionContext): Future[T] = {
    foldLeftUntil(items)(accumulator) { case (acc, item) => fMap(acc, item).imap((_, false)) }
  }

  def foldLeftUntil[I, T](items: Iterable[I], promised: Promise[T] = Promise[T]())(acc: T)(fMap: (T, I) => Future[(T, Boolean)])(implicit ec: ScalaExecutionContext): Future[T] = {
    if (items.isEmpty) { promised.success(acc) }
    else {
      val item = items.head
      fMap(acc, item).onComplete {
        case Success((updatedAcc, done)) =>
          if (done)
            promised.success(updatedAcc)
          else
            foldLeftUntil(items.tail, promised)(updatedAcc)(fMap)
        case Failure(ex) =>
          promised.failure(ex)
      }
    }
    promised.future
  }

  def exists[I](items: Iterable[I])(predicate: I => Future[Boolean])(implicit ec: ScalaExecutionContext): Future[Boolean] = {
    foldLeftUntil(items)(false) { case (_, item) => predicate(item).imap(found => (found, found)) }
  }

  def doUntil(body: => Future[Boolean])(implicit ec: ScalaExecutionContext): Future[Unit] = {
    foldLeftUntil(Stream.continually(()))(()) { case (_, _) => body.imap(done => ((), done)) }
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
      case Success(false) => p.success(())
      case Failure(ex) => p.failure(ex)
    }
    p.future
  }

  // somewhat specialized (short-circuitry); pure side-effects
  def processWhile[T](futures: Iterable[Future[T]], predicate: T => Boolean, promised: Promise[Unit] = Promise())(implicit ec: ScalaExecutionContext): Future[Unit] = {
    futures.headOption match {
      case None => promised.success(())
      case Some(f) => f.onComplete {
        case Success(res) =>
          if (predicate(res)) processWhile(futures.tail, predicate, promised)
          else promised.success(())
        case Failure(t) => promised.failure(t)
      }
    }
    promised.future
  }

  private val identityFuture = (x: Any) => Future.successful(x)

  // lazily transform an input Seq and filter by a supplied predicate
  // useful when a transform op involves an expensive Future and you need to limit the # of futures
  // that are spawned until you have enough
  def findMatching[A, B](in: Seq[A], n: Int, predicate: B => Boolean, transform: A => Future[B] = identityFuture,
    seed: Seq[B] = Seq.empty)(implicit ec: ScalaExecutionContext): Future[Seq[B]] = {
    if (seed.length >= n) Future.successful(seed)
    else in.headOption match {
      case Some(head) =>
        transform(head).flatMap { s =>
          findMatching[A, B](in.tail, n, predicate, transform, if (predicate(s)) seed :+ s else seed)
        }
      case None => Future.successful(seed)
    }
  }

}

