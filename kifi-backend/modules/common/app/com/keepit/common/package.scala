package com.keepit

package object common {

  /** Useful when wanting to side-effect (log, stats, etc) and return the original value.
    * Lets us rewrite things like:
    * {{{
    *   var someVal = func()
    *   log.info(someVal)
    *   someVal
    * }}}
    * as:
    * {{{
    *   func() tap log.info
    * }}}
    */
  implicit class KestrelCombinator[A](val a: A) extends AnyVal {
    def withSideEffect(fun: A => Unit): A = { fun(a); a }
    def tap(fun: A => Unit): A = withSideEffect(fun)

    def withComputation[B](fun: A => B): (A, B) = { val b = fun(a); (a, b) }
    def tapWith[B](fun: A => B): (A, B) = withComputation(fun)
  }

  implicit class ForkCombinator[A, B](val a: A) extends AnyVal {
    def fork(t: A => Boolean)(y: A => B, z: A => B) = {
      if (t(a)) y(a)
      else z(a)
    }
  }

  implicit class Recoverable[A](f: => A) {
    def recover(g: Throwable => A): A = {
      try { f }
      catch {
        case t: Throwable => g(t)
      }
    }
  }

  import com.keepit.common.concurrent.ExecutionContext
  import scala.concurrent.Future
  implicit class CoMap[T](f: => Future[T]) {
    def comap[S](g: T => S): Future[S] = {
      f.map(g)(ExecutionContext.immediate)
    }
  }
}
