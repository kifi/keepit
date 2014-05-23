package com.keepit.common.concurrent

import org.specs2.mutable.Specification
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import com.keepit.common.logging.Logging

class FutureHelpersTest extends Specification with Logging {

  implicit val execCtx = ExecutionContext.fj

  val counter = new AtomicInteger(0)
  def incAndGet:Future[Int] = Future {
    Thread.sleep(util.Random.nextInt(100))
    counter.incrementAndGet
  }

  "sequentialExec" should {
    "execute futures sequentially" in {
      val futures = Seq.fill[() => Future[Int]](20) { () => incAndGet }
      val res = Await.result(FutureHelpers.sequentialExec(futures), Duration.Inf)
      res.sorted === res
    }
  }

}
