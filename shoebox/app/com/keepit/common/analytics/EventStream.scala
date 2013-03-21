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
import com.keepit.common.db.slick.Database

@Singleton
class EventStream {
  implicit val timeout = Timeout(1 second)

  lazy val default = Akka.system.actorOf(Props[EventStreamActor])

  def newStream(): Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (default ? NewStream).map {
      case Connected(enumerator) =>
        // Since we're expecting no input from the client, just consume and discard the input
        val iteratee = Iteratee.foreach[JsValue]{ s => default ! ReplyEcho }
        (iteratee, enumerator)
    }
  }

  def streamEvent(event: Event) = default ! BroadcastEvent(event)

}

object EventWriter {
  implicit val writes = new Writes[Event] {
    def writes(event: Event): JsValue = {
      event match {
        case Event(_,UserEventMetadata(eventFamily,eventName,externalUser,_,experiments,metaData,_),createdAt,_) =>
          val (user, social) = inject[Database].readOnly { implicit session =>
            val user = inject[UserRepo].get(externalUser)
            val social = inject[SocialUserInfoRepo].getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
            (user, social)
          }
          Json.obj(
            "user" -> Json.obj(
              "id" -> user.id.get.id,
              "name" -> s"${user.firstName} ${user.lastName}",
              "avatar" -> s"https://graph.facebook.com/${social}/picture?height=150&width=150"),
            "time" -> createdAt.toStandardTimeString,
            "name" -> eventName,
            "family" -> eventFamily.name
          )
        case _ => JsNull
      }
    }
  }
}

class EventStreamActor extends FortyTwoActor {
  val (eventEnumerator, eventChannel) = Concurrent.broadcast[JsValue]

  implicit val eventWriter = EventWriter.writes

  def receive = {
    case NewStream =>
      sender ! Connected(eventEnumerator)
    case BroadcastEvent(event) =>
      notifyAll(event)
    case ReplyEcho =>
      eventChannel.push(Json.obj("echo" -> System.currentTimeMillis.toString))
  }

  def notifyAll(event: Event) {

    event match {
      case Event(_,UserEventMetadata(eventFamily,eventName,externalUser,_,experiments,metaData,_),createdAt,_) =>
        eventChannel.push(Json.toJson(event))
      case _ =>
    }

  }
}

private trait EventStreamMessage
private case class BroadcastEvent(event: Event) extends EventStreamMessage
private case object ReplyEcho
