package com.keepit

package object common {

  implicit def kestrelCombinator[A](a: A) = new {
    /* Useful when wanting to side-effect (log, stats, etc) and want to return the original value.
     * Lets us rewrite things like:
     *   var someVal = func()
     *   log.info(someVal)
     *   someVal
     * as:
     *   func() tap log.info
     */
    def withSideEffect(fun: A => Unit): A = { fun(a); a }
    def tap(fun: A => Unit): A = withSideEffect(fun)
  }
}
