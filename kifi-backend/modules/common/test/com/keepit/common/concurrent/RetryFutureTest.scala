package com.keepit.common.concurrent

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class RetryFutureTest extends Specification {

  class ExecCtx(underlying: ExecutionContext) extends ExecutionContext {
    var count = 0

    override def execute(runnable: Runnable): Unit = {
      count += 1
      underlying.execute(runnable)
    }
    override def reportFailure(t: Throwable): Unit = underlying.reportFailure(t)
    override def prepare(): ExecutionContext = this
  }

  class Minor extends Exception
  class Major extends Exception

  def mkCtx: ExecCtx = {
    val underlying = play.api.libs.concurrent.Execution.Implicits.defaultContext
    new ExecCtx(underlying.prepare)
  }

  def mkFuture(result: Try[Int], ctx: ExecutionContext): Future[Int] = {
    val p = Promise[Int]
    ctx.execute(new Runnable { def run(): Unit = p.tryComplete(result) })
    p.future
  }

  "RetryFuture" should {
    "complete after a few attempts" in {

      var ctx = mkCtx
      var r: Iterator[Try[Int]] = Seq(Success(10)).iterator
      var f = RetryFuture(3) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) === 10
      ctx.count === 1

      ctx = mkCtx
      r = Seq(Failure(new Exception()), Success(20)).iterator
      f = RetryFuture(3) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) === 20
      ctx.count === 2

      ctx = mkCtx
      r = Seq(Failure(new Exception()), Failure(new Exception()), Success(30)).iterator
      f = RetryFuture(3) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) === 30
      ctx.count === 3

      ctx = mkCtx
      r = Seq(Failure(new Exception()), Failure(new Exception()), Failure(new Exception()), Success(40)).iterator
      f = RetryFuture(3) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) must throwA[Exception]
      ctx.count === 3
    }

    "retry only when an exception is resolved" in {
      var ctx = mkCtx
      var r: Iterator[Try[Int]] = Seq(Failure(new Minor), Success(10)).iterator
      var f = RetryFuture(3, { case e: Minor => true }) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) === 10
    }

    "fail when an exception is not resolved" in {
      var ctx = mkCtx
      var r: Iterator[Try[Int]] = Seq(Failure(new Major), Success(10)).iterator
      var f = RetryFuture(3, { case e: Major => false }) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) must throwA[Major]
    }

    "fail when an exception is not recognized" in {
      var ctx = mkCtx
      var r: Iterator[Try[Int]] = Seq(Failure(new Major), Success(10)).iterator
      var f = RetryFuture(3, { case e: Minor => true }) { mkFuture(r.next, ctx) }

      Await.result(f, Duration.Inf) must throwA[Major]
    }
  }
}
