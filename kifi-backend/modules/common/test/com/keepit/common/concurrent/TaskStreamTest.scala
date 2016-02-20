package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import org.specs2.mutable.Specification

import com.keepit.common.core.futureExtensionOps
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class TaskStreamTest extends Specification with Logging {

  implicit val execCtx = ExecutionContext.fj

  "TaskStream" should {
    "run futures lazily" in {
      "simple squaring" in {
        val processed = mutable.ArrayBuffer.empty[Int]
        def slowFut(x: Int) = Future {
          Thread.sleep(10 * x)
          processed.append(x)
          x * x
        }
        def fast(x: Int) = x * x

        val inputs = Seq(5, 4, 3, 2, 1)
        val ts = TaskStream(inputs)(slowFut)
        Await.result(ts.head, Duration.Inf) === 5 * 5
        processed.toSeq === Seq(5)

        Await.result(ts.seq, Duration.Inf) === inputs.map(fast)
        processed.toSeq === inputs

        Await.result(ts.seq, Duration.Inf) === inputs.map(fast)
      }
    }
    "impersonate FutureHelpers" in {
      "foldLeft #1" in {
        def sqFut(x: Int) = Future.successful(x * x)
        val inputs = Seq.range(1, 10)
        val ts = TaskStream(inputs)(sqFut).foldLeft(0)(_ + _)
        val fh = FutureHelpers.foldLeft(inputs)(0) { (acc, inp) => sqFut(inp).imap(acc + _) }
        Await.result(ts, Duration.Inf) === Await.result(fh, Duration.Inf)
      }
      "foldLeft #2" in {
        val fakeShoebox = Map(1 -> "ryan", 2 -> "léo", 3 -> "andrew", 4 -> "cam")
        def fakeGetUser(id: Int): Future[Option[String]] = Future.successful(fakeShoebox.get(id))

        val inputs = Seq(3, 1, 4)
        val ts = TaskStream(inputs)(i => fakeGetUser(i).imap(i -> _)).foldLeft(Map.empty[Int, Option[String]])(_ + _)
        val fh = FutureHelpers.foldLeft(inputs)(Map.empty[Int, Option[String]]) {
          case (acc, i) => fakeGetUser(i).imap(v => acc + (i -> v))
        }
        Await.result(ts, Duration.Inf) === Await.result(fh, Duration.Inf)
      }
      "collect first #1" in {
        def sqFut(x: Int) = Future.successful(x * x)
        val inputs = Seq.range(1, 10)
        val ts = TaskStream(inputs)(sqFut)

        val fhFirst = Await.result(FutureHelpers.collectFirst(inputs) { x => sqFut(x).imap(v => Some(v).filter(_ > 20)) }, Duration.Inf)
        val tsFirst = Await.result(ts.find(_ >= 20), Duration.Inf)
        fhFirst === Some(25)
        fhFirst === tsFirst
      }
    }
    "do cool things that might be hard with other methods" in {
      "3n+1" in {
        def collatz(n: Int) = Future.successful(if (n % 2 == 0) n / 2 else 3 * n + 1)
        val ts = TaskStream.iterate(3)(collatz)
        val firstCycle = Await.result(ts.take(8), Duration.Inf)
        firstCycle === Seq(3, 10, 5, 16, 8, 4, 2, 1)
      }
    }
    "do cool memoized things" in {
      "sum and product" in {
        var fnCallCnt = 0
        def sqFut(x: Int) = {
          fnCallCnt += 1
          Future.successful(x * x)
        }
        val inputs = Seq.range(1, 10)
        val ts = TaskStream(inputs)(sqFut)

        fnCallCnt === 0
        val fhSum = Await.result(FutureHelpers.foldLeft(inputs)(0) { (acc, inp) => sqFut(inp).imap(acc + _) }, Duration.Inf)
        fnCallCnt === inputs.length
        val fhProd = Await.result(FutureHelpers.foldLeft(inputs)(1) { (acc, inp) => sqFut(inp).imap(acc * _) }, Duration.Inf)
        fnCallCnt === 2 * inputs.length

        val tsSum = Await.result(ts.foldLeft(0)(_ + _), Duration.Inf)
        fnCallCnt === 3 * inputs.length
        val tsProd = Await.result(ts.foldLeft(1)(_ * _), Duration.Inf)
        fnCallCnt === 3 * inputs.length

        tsSum === fhSum
        tsProd === fhProd
      }
    }
  }
}
