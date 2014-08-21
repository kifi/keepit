package com.keepit.common.concurrent

import org.specs2.mutable.Specification
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import com.keepit.common.logging.Logging
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import com.keepit.common.core._

class FutureHelpersTest extends Specification with Logging {

  implicit val execCtx = ExecutionContext.fj

  "sequentialExec" should {

    "sequentially execute" in {
      val lock = new ReentrantLock()
      val builder = mutable.ArrayBuilder.make[Int]
      val input = Seq.fill(100) { util.Random.nextInt(Int.MaxValue) }
      val resF = FutureHelpers.sequentialExec(input) { id =>
        Future {
          val held = lock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access! lock=${lock}")
          builder += id
          lock.unlock()
        }
      }
      Await.result(resF, Duration.Inf)
      builder.result().toSeq === input
    }

    "partial process (i.e. short-circuitry)" in {
      val counter = new AtomicInteger(0)
      def generate: Future[Int] = Future.successful {
        counter.incrementAndGet()
      }
      val futures = Seq.fill(10) { generate }
      var curr = 0
      val resF = FutureHelpers.processWhile(futures, { (i: Int) =>
        (i < 5) tap { _ => curr = i }
      })
      Await.result(resF, Duration.Inf)
      curr === 5
    }

  }

}

