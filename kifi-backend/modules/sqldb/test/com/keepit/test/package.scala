package com.keepit

import org.specs2.execute.{ Results, Result, AsResult }

package object test {
  implicit def unitAsResult: AsResult[Unit] = new AsResult[Unit] {
    def asResult(t: => Unit): Result = Results.toResult(true)
  }
}
