package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class TaskStreamTest extends Specification with Logging {

  implicit val execCtx = ExecutionContext.fj

  "TaskStream" should {
    "run futures lazily" in {
      val processed = mutable.ArrayBuffer.empty[Int]
      def slowFut(x: Int) = Future {
        println(s"Processing $x")
        Thread.sleep(10 * x)
        val ans = x * x
        println(s"Yielding $x -> $ans")
        processed.append(x)
        ans
      }
      def fast(x: Int) = x * x

      val inputs = Seq(5, 4, 3, 2, 1)
      val ts = new TaskStream(inputs, slowFut)
      Await.result(ts.head, Duration.Inf) === 5 * 5
      processed.toSeq === Seq(5)

      Await.result(ts.take(5), Duration.Inf) === inputs.map(fast)
      processed.toSeq === inputs

      Await.result(ts.take(5), Duration.Inf) === inputs.map(fast)
    }
  }
}
