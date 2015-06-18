package com.keepit.common

import com.keepit.common.concurrent.ExecutionContext

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
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
  @inline def recover(g: PartialFunction[Throwable, A]): A = {
    try f catch {
      case ex: Throwable if g.isDefinedAt(ex) => g(ex)
      case ex: Throwable => throw ex
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

final class IterableExtensionOps[A, Repr](xs: IterableLike[A, Repr]) {
  def distinctBy[B, That](f: A => B)(implicit cbf: CanBuildFrom[Repr, A, That]) = {
    val builder = cbf(xs.repr)
    val i = xs.iterator
    var set = Set[B]()
    while (i.hasNext) {
      val o = i.next()
      val b = f(o)
      if (!set(b)) {
        set += b
        builder += o
      }
    }
    builder.result()
  }
}

final class TraversableOnceExtensionOps[A](xs: TraversableOnce[A]) {
  def maxOpt(implicit cmp: Ordering[A]): Option[A] = if (xs.isEmpty) None else Option(xs.max)
  def minOpt(implicit cmp: Ordering[A]): Option[A] = if (xs.isEmpty) None else Option(xs.min)
  def maxByOpt[B](f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (xs.isEmpty) None else Option(xs.maxBy(f))
  }
  def minByOpt[B](f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (xs.isEmpty) None else Option(xs.minBy(f))
  }
}

trait Implicits {
  implicit def anyExtensionOps[A](x: A): AnyExtensionOps[A] = new AnyExtensionOps[A](x)
  implicit def tryExtensionOps[A](x: scala.util.Try[A]): TryExtensionOps[A] = new TryExtensionOps[A](x)
  implicit def funcExtensionOps[A](x: => A): FuncExtensionOpts[A] = new FuncExtensionOpts[A](x)
  implicit def futureExtensionOps[A](x: => Future[A]): FutureExtensionOps[A] = new FutureExtensionOps[A](x)
  implicit def iterableExtensionOps[A, Repr](xs: IterableLike[A, Repr]): IterableExtensionOps[A, Repr] = new IterableExtensionOps(xs)
  implicit def traversableOnceExtensionOps[A](xs: TraversableOnce[A]): TraversableOnceExtensionOps[A] = new TraversableOnceExtensionOps(xs)
}

