package com.keepit.eliza.ws

import com.keepit.eliza.social.FakeSecureSocial
import org.specs2.mutable.Specification
import org.specs2.matcher.{ MatchResult, Expectable, Matcher }
import org.specs2.time.NoTimeConversions
import play.api.libs.iteratee.{ Enumeratee, Iteratee, Enumerator }
import play.api.mvc.WebSocket
import play.api.test.FakeRequest

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise, Future }

trait WebSocketTest[A] extends Specification with NoTimeConversions {

  val socketIn: mutable.MutableList[() => A] = mutable.MutableList()

  def feedToSocket(ws: WebSocket[A, A]): Future[Seq[A]] = {
    val feed = (Enumerator.enumerate(socketIn) through Enumeratee.map { fn =>
      fn()
    }) andThen Enumerator.eof

    //    val feed = Enumerator.eof[A]

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

  def in(that: () => A) = {
    socketIn += that
  }

  def in(that: A) = {
    socketIn += { () => that }
  }

  def socketOutput(timeout: FiniteDuration = 60.seconds)(implicit ws: WebSocket[A, A]): Seq[A] = {
    Await.result(feedToSocket(ws), timeout)
  }

  def socketOutput(implicit ws: WebSocket[A, A]): Seq[A] = socketOutput()(ws)

}
