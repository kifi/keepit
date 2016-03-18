package com.keepit.common.util

final class RightBias[L, R](ethr: Either[L, R]) {
  def foreach(f: R => Unit): Unit = ethr.right.foreach(f)
  def map[R1](f: R => R1): RightBias[L, R1] = new RightBias(ethr.right.map(f))
  def flatMap[R1](f: R => RightBias[L, R1]): RightBias[L, R1] = ethr.fold[RightBias[L, R1]](l => RightBias.left(l), r => f(r))
  def fold[T](lf: L => T, rf: R => T): T = ethr.fold(lf, rf)
}

object RightBias {
  val unit: RightBias[Nothing, Unit] = right(())

  def cond[L, R](test: => Boolean, r: => R, l: => L): RightBias[L, R] = new RightBias(Either.cond(test, r, l))
  def right[L, R](r: => R): RightBias[L, R] = new RightBias(Right(r))
  def left[L, R](l: => L): RightBias[L, R] = new RightBias(Left(l))

  final implicit class FromOption[R](opt: Option[R]) {
    def withLeft[L](l: L): RightBias[L, R] = opt.fold[RightBias[L, R]](left(l))(r => right(r))
  }

  object LeftSide { def unapply[L, R](rb: RightBias[L, R]): Option[L] = rb.fold(l => Some(l), r => None) }
  object RightSide { def unapply[L, R](rb: RightBias[L, R]): Option[R] = rb.fold(l => None, r => Some(r)) }
}
