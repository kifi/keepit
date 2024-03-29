package com.keepit.common

import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.core.iterableExtensionOps
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.json._

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.util.matching.Regex

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

  def min(y: A)(implicit ord: Ordering[A]): A = Seq(x, y).min
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
  def airbrakingOption(implicit airbrake: AirbrakeNotifier): Option[A] = x match {
    case scala.util.Success(t) => Option(t)
    case scala.util.Failure(f) =>
      airbrake.notify(f)
      None
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
  def mapAccumLeft[Acc, B, That](a0: Acc)(fn: (Acc, A) => (Acc, B))(implicit cbf: CanBuildFrom[Repr, B, That]): (Acc, That) = {
    val builder = cbf(xs.repr)
    var acc = a0
    xs.foreach { x =>
      val (newAcc, y) = fn(acc, x)
      acc = newAcc
      builder += y
    }
    (acc, builder.result())
  }

  def flatAugmentWith[Acc, B, That](fn: A => Option[B])(implicit cbf: CanBuildFrom[Repr, (A, B), That]): That = {
    val builder = cbf(xs.repr)
    xs.foreach { x =>
      fn(x).foreach { y => builder += x -> y }
    }
    builder.result()
  }
  def augmentWith[Acc, B, That](fn: A => B)(implicit cbf: CanBuildFrom[Repr, (A, B), That]): That = {
    flatAugmentWith(x => Option(fn(x)))
  }
}

final class TraversableOnceExtensionOps[A](xs: TraversableOnce[A]) {
  def minMaxOpt(implicit cmp: Ordering[A]): Option[(A, A)] = if (xs.isEmpty) None else Option((xs.min, xs.max))
  def maxOpt(implicit cmp: Ordering[A]): Option[A] = if (xs.isEmpty) None else Option(xs.max)
  def minOpt(implicit cmp: Ordering[A]): Option[A] = if (xs.isEmpty) None else Option(xs.min)
  def maxByOpt[B](f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (xs.isEmpty) None else Option(xs.maxBy(f))
  }
  def minByOpt[B](f: A => B)(implicit cmp: Ordering[B]): Option[A] = {
    if (xs.isEmpty) None else Option(xs.minBy(f))
  }
}

final class TraversableExtensionOps[A](xs: Traversable[A]) {
  def countBy[B](fn: A => B): Map[B, Int] = xs.groupBy(fn).mapValues(_.toSeq.length)
  def countAll: Map[A, Int] = countBy(identity)
}

final class MapExtensionOps[A, B](xs: Map[A, B]) {
  def getOrAirbrake(k: A)(implicit airbrake: AirbrakeNotifier) = {
    val v = xs.get(k)
    if (v.isEmpty) airbrake.notify(new Exception(s"Key $k is not in map, and we really think it should be there"))
    v
  }
  def mapValuesStrict[C](fn: B => C): Map[A, C] = xs.map { case (k, v) => k -> fn(v) }
  def flatMapValues[C](fn: B => Option[C]): Map[A, C] = xs.flatMap { case (k, v) => fn(v).map(k -> _) }
  def filterValues(predicate: B => Boolean): Map[A, B] = xs.filter { case (k, v) => predicate(v) }
  def traverseByKey(implicit ord: Ordering[A]): Seq[B] = {
    xs.toSeq.sortBy(_._1).map(_._2)
  }
}

final class EitherExtensionOps[A, B, Repr](xs: IterableLike[Either[A, B], Repr]) {
  def partitionEithers[AThat, BThat](implicit cbfA: CanBuildFrom[Repr, A, AThat], cbfB: CanBuildFrom[Repr, B, BThat]) = {
    val as = cbfA()
    val bs = cbfB()
    xs.foreach {
      case Left(a) => as += a
      case Right(b) => bs += b
    }
    (as.result(), bs.result())
  }
}

final class OptionExtensionOpts[A](x: Option[A]) {
  def containsTheSameValueAs(y: Option[A]) = (x, y) match {
    case (Some(v1), Some(v2)) => v1 == v2
    case _ => false
  }

  // Does not have the liberal [A1 >: A] type bound, so you cannot do really dumb things
  def safely: SafelyTypedOption[A] = new SafelyTypedOption(x)
  final class SafelyTypedOption[T](valOpt: Option[T]) {
    def contains(v: T): Boolean = valOpt.contains(v)
  }
}

final class RegexExtensionOps(r: Regex) {

  // Breaks a string into chunks:
  //     Right(match: Regex.Match) for the matches
  //     Left(chunk: String) for chunks that do not match
  // They are interleaved such that
  //     ```
  //     regex.findMatchesAndInterstitials(originalStr).map {
  //         case Left(chunk) => chunk
  //         case Right(match) => match.source
  //     }.mkString == originalStr
  //     ```
  // See ImplicitsTest for examples
  def findMatchesAndInterstitials(str: String): Seq[Either[String, Regex.Match]] = {
    val (lastIdx, matches) = r.findAllMatchIn(str).toSeq.mapAccumLeft(0) {
      case (idx, m) =>
        (m.end, Seq(Left(str.slice(idx, m.start)), Right(m)))
    }
    (matches.flatten :+ Left(str.drop(lastIdx))).filterNot {
      case Left("") => true
      case _ => false
    }
  }
}

final class JsObjectExtensionOps(x: JsObject) {
  def nonNullFields: JsObject = JsObject(x.fields.filter { case (_, b) => b != JsNull })
}

final class JsValueExtensionOps(x: JsValue) {
  private val zero = BigDecimal(0)
  def isFalsy = {
    x match {
      case JsNull | JsString("") | JsUndefined() | JsBoolean(false) | JsNumber(`zero`) => true
      case _ => false
    }
  }
}

trait Implicits {
  implicit def anyExtensionOps[A](x: A): AnyExtensionOps[A] = new AnyExtensionOps[A](x)
  implicit def tryExtensionOps[A](x: scala.util.Try[A]): TryExtensionOps[A] = new TryExtensionOps[A](x)
  implicit def funcExtensionOps[A](x: => A): FuncExtensionOpts[A] = new FuncExtensionOpts[A](x)
  implicit def futureExtensionOps[A](x: => Future[A]): FutureExtensionOps[A] = new FutureExtensionOps[A](x)
  implicit def iterableExtensionOps[A, Repr](xs: IterableLike[A, Repr]): IterableExtensionOps[A, Repr] = new IterableExtensionOps(xs)
  implicit def traversableOnceExtensionOps[A](xs: TraversableOnce[A]): TraversableOnceExtensionOps[A] = new TraversableOnceExtensionOps(xs)
  implicit def traversableExtensionOps[A](xs: Traversable[A]): TraversableExtensionOps[A] = new TraversableExtensionOps(xs)
  implicit def mapExtensionOps[A, B](xs: Map[A, B]): MapExtensionOps[A, B] = new MapExtensionOps(xs)
  implicit def eitherExtensionOps[A, B, Repr](xs: IterableLike[Either[A, B], Repr]): EitherExtensionOps[A, B, Repr] = new EitherExtensionOps(xs)
  implicit def optionExtensionOps[A, B](x: Option[A]): OptionExtensionOpts[A] = new OptionExtensionOpts(x)
  implicit def regexExtensionOps(r: Regex): RegexExtensionOps = new RegexExtensionOps(r)
  implicit def jsObjectExtensionOps[A](x: JsObject): JsObjectExtensionOps = new JsObjectExtensionOps(x)
  implicit def jsValueExtensionOps[A](x: JsValue): JsValueExtensionOps = new JsValueExtensionOps(x)
}

