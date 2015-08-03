package com.keepit.eliza.ws

import com.keepit.eliza.social.FakeSecureSocial
import play.api.libs.iteratee.{ Concurrent, Enumeratee, Iteratee, Enumerator }
import play.api.mvc.WebSocket
import play.api.test.FakeRequest

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise, Future }
import scala.concurrent.ExecutionContext.global

case class MockWebSocket[A](implicit ws: WebSocket[A, A]) {

  val socketOut: Stream[Promise[A]] = Stream.continually(Promise())

  val itOut = Iteratee.fold[A, Int](0) { (index, output) =>
    socketOut(index).success(output)
    index + 1
  }(global).map { _ => () }(global)

  val (enumOut, channel) = Concurrent.broadcast[A]

  val wsResult = Await.result(ws.f(FakeRequest("GET", "/ws?sid=" + FakeSecureSocial.FAKE_SID)), 10 seconds)
  val wsFunction = wsResult.right.get
  wsFunction(enumOut, itOut)

  def in(block: => A): Unit = channel.push(block)

  var outIndex = 0

  def out: A = {
    val result = Await.result(socketOut(outIndex).future, 10 seconds)
    outIndex += 1
    result
  }

  def close: Unit = {
    channel.eofAndEnd()
  }

}

