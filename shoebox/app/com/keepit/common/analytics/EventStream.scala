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
class EventStream @Inject() (eventWriter: EventWriter) {
  implicit val timeout = Timeout(1 second)

  lazy val default = Akka.system.actorOf(Props {new EventStreamActor(eventWriter) })

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

class EventWriter @Inject() (db: Database, userRepo: UserRepo, socialUserInfoRepo: SocialUserInfoRepo){
  implicit val writesUserEvent = new Writes[WrappedUserEvent] {
    def writes(wrapped: WrappedUserEvent): JsValue = {
      Json.obj(
        "user" -> Json.obj(
          "id" -> wrapped.user.id.get.id,
          "name" -> s"${wrapped.user.firstName} ${wrapped.user.lastName}",
          "avatar" -> s"https://graph.facebook.com/${wrapped.social}/picture?height=150&width=150"),
        "time" -> wrapped.createdAt.toStandardTimeString,
        "name" -> wrapped.eventName,
        "family" -> wrapped.eventFamily.name
      )
    }
  }
  case class WrappedUserEvent(event: Event, user: User, social: String, eventName: String, eventFamily: EventFamily, createdAt: DateTime)

  def toJson(event: Event): JsValue = {
      event match {
        case Event(_,UserEventMetadata(eventFamily,eventName,externalUser,_,experiments,metaData,_),createdAt,_) =>
          val (user, social) = db.readOnly { implicit session =>
            val user = userRepo.get(externalUser)
            val social = socialUserInfoRepo.getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
            (user, social)
          }

          Json.toJson(WrappedUserEvent(event, user, social, eventName, eventFamily, createdAt))
        case _ => JsNull
      }
  }
}

class EventStreamActor(eventWriter: EventWriter) extends FortyTwoActor {
  val (eventEnumerator, eventChannel) = Concurrent.broadcast[JsValue]

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
        eventChannel.push(eventWriter.toJson(event))
      case _ =>
    }

  }
}

private trait EventStreamMessage
private case class BroadcastEvent(event: Event) extends EventStreamMessage
private case object ReplyEcho
