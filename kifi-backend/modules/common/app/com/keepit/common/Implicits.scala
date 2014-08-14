package com.keepit.common

import com.keepit.common.concurrent.ExecutionContext

import scala.concurrent.Future

final class AnyExtensionOps[A](val x: A) extends AnyVal {
  // forward pipe operator, analogous to the Unix pipe.
  // Uses symbolic method name for order-of-operation reasons (and ubiquity of |)
  // expr |> updateA |> updateB |> save
  @inline def |>[B](f: A => B): B = f(x)

  // Kestrel Combinator, tee operator
  // expr tap println
  @inline def tap(f: A => Unit): A = {
    f(x)
    x
  }
}

final class FuncExtensionOpts[A](f: => A) {
  @inline def recover(g: Throwable => A): A = {
    try { f }
    catch {
      case t: Throwable => g(t)
    }
  }
}

final class TryExtensionOps[A](val x: scala.util.Try[A]) extends AnyVal {
  def fold[B](f: A => B, g: Throwable => B): B = x match {
    case scala.util.Success(t) => f(t)
    case scala.util.Failure(t) => g(t)
  }
}

final class FutureExtensionOps[A](x: => Future[A]) {
  def imap[S](g: A => S): Future[S] = {
    x.map(g)(ExecutionContext.immediate)
  }
}

trait Implicits {
  implicit def anyExtensionOps[A](x: A): AnyExtensionOps[A] = new AnyExtensionOps[A](x)
  implicit def tryExtensionOps[A](x: scala.util.Try[A]): TryExtensionOps[A] = new TryExtensionOps[A](x)
  implicit def funcExtensionOps[A](x: => A): FuncExtensionOpts[A] = new FuncExtensionOpts[A](x)
  implicit def futureExtensionOps[A](x: => Future[A]): FutureExtensionOps[A] = new FutureExtensionOps[A](x)
}
