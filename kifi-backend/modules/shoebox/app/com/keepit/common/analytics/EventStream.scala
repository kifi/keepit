package com.keepit.common.analytics

import scala.concurrent.Future
import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton}
import com.keepit.common.actor.ActorWrapper
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.model._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.json._

@Singleton
class EventStream @Inject() (
    actorWrapper: ActorWrapper[EventStreamActor],
    eventWriter: EventWriter) {
  implicit val timeout = Timeout(1 second)

  def newStream(): Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (actorWrapper.actor ? NewStream).map {
      case Connected(enumerator) =>
        // Since we're expecting no input from the client, just consume and discard the input
        val iteratee = Iteratee.foreach[JsValue]{ s => actorWrapper.actor ! ReplyEcho }
        (iteratee, enumerator)
    }
  }

  def streamEvent(event: Event) = {
    implicit val writes = eventWriter.writesUserEvent
    eventWriter.wrapEvent(event).map { wrappedEvent =>
      // Todo(Andrew): Wire up to new WS
      //adminEvent.broadcast("event", Json.toJson(wrappedEvent))
      actorWrapper.actor ! BroadcastEvent(Json.toJson(wrappedEvent))
    }
  }

}

class EventWriter @Inject() (
    db: Database,
    userRepo: UserRepo,
    imageStore: S3ImageStore) {
  implicit val writesUserEvent = new Writes[WrappedUserEvent] {
    def writes(wrapped: WrappedUserEvent): JsValue = {
      Json.obj(
        "user" -> Json.obj(
          "id" -> wrapped.user.id.get.id,
          "name" -> s"${wrapped.user.firstName} ${wrapped.user.lastName}",
          "avatar" -> wrapped.avatarUrl
        ),
        "time" -> wrapped.createdAt.toStandardTimeString,
        "name" -> wrapped.eventName,
        "family" -> wrapped.eventFamily.name
      )
    }
  }

  case class WrappedUserEvent(
    event: Event,
    user: User,
    avatarUrl: Option[String],
    eventName: String,
    eventFamily: EventFamily,
    createdAt: DateTime)

  def wrapEvent(event: Event): Option[WrappedUserEvent] = {
      event match {
        case Event(_,UserEventMetadata(eventFamily, eventName, externalUser, _, experiments, metaData, _), createdAt, _) =>
          val user = db.readOnly { implicit session => userRepo.get(externalUser) }
          val avatarUrl = imageStore.getPictureUrl(150, user).value.flatMap(_.toOption)
          Some(WrappedUserEvent(event, user, avatarUrl, eventName, eventFamily, createdAt))
        case _ => None
      }
  }
}

class EventStreamActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    eventWriter: EventWriter)
  extends FortyTwoActor(healthcheckPlugin) {

  val (eventEnumerator, eventChannel) = Concurrent.broadcast[JsValue]

  def receive = {
    case NewStream =>
      sender ! Connected(eventEnumerator)
    case BroadcastEvent(event) =>
      notifyAll(event)
    case ReplyEcho =>
      eventChannel.push(Json.obj("echo" -> System.currentTimeMillis.toString))
  }

  def notifyAll(event: JsValue) {
    eventChannel.push(event)
  }
}

private trait EventStreamMessage
private case object NewStream extends EventStreamMessage
private case class Connected(enumerator: Enumerator[JsValue]) extends EventStreamMessage
private case class BroadcastEvent(event: JsValue) extends EventStreamMessage
private case object ReplyEcho
