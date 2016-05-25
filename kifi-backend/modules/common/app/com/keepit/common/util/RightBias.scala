package com.keepit.common.util

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
import scala.util.{Success, Failure, Try}

// A right-biased either. Implemented as a pretty lightweight wrapper around Either
final class RightBias[+L, R](ethr: Either[L, R]) {
  def getRight: Option[R] = ethr.right.toOption
  def getLeft: Option[L] = ethr.left.toOption
  def foreach(f: R => Unit): Unit = ethr.right.foreach(f)
  def fold[T](lf: L => T, rf: R => T): T = ethr.fold(lf, rf)
  def map[R1](f: R => R1): RightBias[L, R1] =
    fold(l => RightBias.left(l), r => RightBias.right(f(r)))
  def flatMap[L1 >: L, R1](f: R => RightBias[L1, R1]): RightBias[L1, R1] =
    fold(l => RightBias.left(l), r => f(r))
  def getOrElse(fallback: L => R): R =
    fold(fallback, identity)

  def mapLeft[L1](f: L => L1): RightBias[L1, R] =
    fold(l => RightBias.left(f(l)), r => RightBias.right(r))
  def filter[L1](test: R => Boolean, fallback: => L1): RightBias[L1, R] = ethr match {
    case Right(r) if test(r) => RightBias.right(r)
    case _ => RightBias.left(fallback)
  }

  def asTry(implicit ev: L <:< Throwable): Try[R] = fold(Failure(_), Success(_))
}

object RightBias {
  def unit[L]: RightBias[L, Unit] = right(())

  def cond[L, R](test: => Boolean, r: => R, l: => L): RightBias[L, R] = new RightBias(Either.cond(test, r, l))
  def right[L, R](r: => R): RightBias[L, R] = new RightBias(Right(r))
  def left[L, R](l: => L): RightBias[L, R] = new RightBias(Left(l))

  final implicit class FromOption[R](opt: Option[R]) {
    def withLeft[L](l: L): RightBias[L, R] = opt.fold[RightBias[L, R]](left(l))(r => right(r))
  }

  private def fragileMapHelper[T, L, R, TS, RS](xs: IterableLike[T, TS], f: T => RightBias[L, R])(implicit cbf: CanBuildFrom[TS, R, RS]): RightBias[L, RS] = {
    val rbuilder = cbf(xs.repr)
    val xsIt = xs.iterator
    var firstLeft = Option.empty[L]
    while (xsIt.hasNext && firstLeft.isEmpty) {
      f(xsIt.next()) match {
        case LeftSide(l) => firstLeft = Some(l)
        case RightSide(r) => rbuilder += r
      }
    }
    firstLeft match {
      case Some(l) => RightBias.left(l)
      case None => RightBias.right(rbuilder.result())
    }
  }
  final implicit class FromSeq[T](xs: Seq[T]) {
    def fragileMap[L, R](f: T => RightBias[L, R]): RightBias[L, Seq[R]] = fragileMapHelper(xs, f)
  }
  final implicit class FromSet[T](xs: Set[T]) {
    def fragileMap[L, R](f: T => RightBias[L, R]): RightBias[L, Set[R]] = fragileMapHelper(xs, f)
  }

  object LeftSide { def unapply[L](rb: RightBias[L, _]): Option[L] = rb.getLeft }
  object RightSide { def unapply[R](rb: RightBias[_, R]): Option[R] = rb.getRight }
}
