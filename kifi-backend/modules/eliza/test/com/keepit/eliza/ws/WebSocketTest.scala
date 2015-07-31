package com.keepit.eliza.ws

import com.keepit.eliza.social.FakeSecureSocial
import org.specs2.mutable.Specification
import org.specs2.matcher.{ MatchResult, Expectable, Matcher }
import org.specs2.time.NoTimeConversions
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import play.api.mvc.WebSocket
import play.api.test.FakeRequest

import scala.concurrent.duration._
import scala.concurrent.{ Promise, Future }

trait WebSocketTest extends Specification with NoTimeConversions {

  def feedToSocket[A](inputs: Seq[A], ws: WebSocket[A, A]): Future[Seq[A]] = {
    val feed = Enumerator.enumerate(inputs) andThen Enumerator.eof // use a delayed end so that everything can process in time
    val resultsPromise = Promise[Seq[A]]()

    val out = Iteratee.getChunks[A].map { outputs =>
      resultsPromise.success(outputs)
    }.recover {
      case thrown: Throwable =>
        resultsPromise.failure(thrown)
    }.map(_ => ())

    ws.f(FakeRequest("GET", "/ws?sid=" + FakeSecureSocial.FAKE_SID)).flatMap {
      case Right(fn) =>
        fn(feed, out)
        resultsPromise.future
      case Left(result) =>
        resultsPromise.failure(new RuntimeException("Result " + result + " was not expected"))
        resultsPromise.future
    }
  }

  class WebSocketMatcher[A](m: Matcher[Seq[A]], retries: Int = 0, timeout: FiniteDuration = 60.seconds)(implicit ws: WebSocket[A, A]) extends Matcher[Seq[A]] {

    override def apply[N <: Seq[A]](t: Expectable[N]): MatchResult[N] = {
      m.await(retries, timeout).apply(t.map { value: Seq[A] =>
        feedToSocket(value, ws)
      }).asInstanceOf[MatchResult[N]]
    }

    def after(newRetries: Int = 0, newTimeout: FiniteDuration = 60.seconds) = new WebSocketMatcher(m, newRetries, newTimeout)

  }

  def leadToSocketOutput[A](m: Matcher[Seq[A]])(implicit ws: WebSocket[A, A]): WebSocketMatcher[A] = new WebSocketMatcher(m)

}
