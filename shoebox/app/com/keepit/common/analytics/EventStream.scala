package com.keepit.common.analytics

import akka.actor._
import scala.concurrent.duration._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.akka.FortyTwoActor
import scala.concurrent.Future
import com.keepit.serializer.EventSerializer


class EventStream {
  implicit val timeout = Timeout(1 second)

  lazy val default = Akka.system.actorOf(Props[EventStreamActor])

  def newStream(): Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (default ? NewStream).map {
      case Connected(enumerator) =>
        // Since we're expecting no input from the client, just consume and discard the input
        val iteratee = Iteratee.consume[JsValue]()

        (iteratee, enumerator)

      case CannotConnect(error) =>
        val iteratee = Done[JsValue,Unit]((),Input.EOF)
        val enumerator = Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))

        (iteratee, enumerator)
    }
  }

}

class EventStreamActor extends FortyTwoActor {
  val (eventEnumerator, eventChannel) = Concurrent.broadcast[JsValue]


  def receive = {
    case NewStream =>
      sender ! Connected(eventEnumerator)
    case BroadcastEvent(event: Event) =>
      notifyAll(event)
  }

  def notifyAll(event: Event) {
    val msg = JsObject(
      Seq(
        "event" -> EventSerializer.eventSerializer.writes(event)
      )
    )
    eventChannel.push(msg)
  }
}

trait EventStreamMessage
case object NewStream extends EventStreamMessage
case class Connected(enumerator: Enumerator[JsValue]) extends EventStreamMessage
case class CannotConnect(errorMsg: String) extends EventStreamMessage
case class BroadcastEvent(event: Event)


