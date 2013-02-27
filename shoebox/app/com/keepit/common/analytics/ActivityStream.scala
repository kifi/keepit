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
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.model._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.inject._

@Singleton
class ActivityStream {
  implicit val timeout = Timeout(1 second)

  lazy val default = Akka.system.actorOf(Props[ActivityStreamActor])

  def newStream(): Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (default ? NewStream).map {
      case Connected(enumerator) =>
        // Since we're expecting no input from the client, just consume and discard the input
        val iteratee = Iteratee.foreach[JsValue]{ s => /* ignore for now */ }

        (iteratee, enumerator)

      case CannotConnect(error) =>
        val iteratee = Done[JsValue,Unit]((),Input.EOF)
        val enumerator = Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))

        (iteratee, enumerator)
    }
  }

  def streamActivity(kind: String, json: JsObject) = default ! BroadcastActivity(kind, json)

}

class ActivityStreamActor extends FortyTwoActor {
  val (activityEnumerator, activityChannel) = Concurrent.broadcast[JsValue]


  def receive = {
    case NewStream =>
      sender ! Connected(activityEnumerator)
    case BroadcastActivity(kind: String, json: JsObject) =>
      notifyAll(kind, json)
  }

  def notifyAll(kind: String, json: JsObject) {
    val msg = Json.obj(
        "kind" -> kind,
        "time" -> inject[DateTime].toStandardTimeString,
        "activity" -> json
    )
    activityChannel.push(msg)
  }
}

trait ActivityStreamMessage
private case object NewStream extends ActivityStreamMessage
private case class Connected(enumerator: Enumerator[JsValue]) extends ActivityStreamMessage
private case class CannotConnect(errorMsg: String) extends ActivityStreamMessage
private case class BroadcastActivity(kind: String, json: JsObject) extends ActivityStreamMessage
