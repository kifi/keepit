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
  }

  implicit class ForkCombinator[A, B](val a: A) extends AnyVal {
    def fork(t: A => Boolean)(y: A => B, z: A => B) = {
      if (t(a)) y(a)
      else z(a)
    }
  }

  implicit class Recoverable[A](f: => A) extends AnyVal {
    def recover(g: Throwable => A): A = {
      try { f }
      catch {
        case t: Throwable => g(t)
      }
    }
  }
}
