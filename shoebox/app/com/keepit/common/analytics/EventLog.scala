package com.keepit.common.analytics

import org.joda.time.DateTime

import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.db.ExternalId
import com.keepit.common.db.State
import com.keepit.common.time._
import com.keepit.inject.inject
import com.keepit.model._

import play.api.Play.current
import play.api.libs.json._

trait EventFamily {
  val name: String
  val collection: String
  override def toString = name
}

case class UserEventFamily(name: String) extends EventFamily {
  val collection = "user"
}

case class ServerEventFamily(name: String) extends EventFamily {
  val collection = "server"
}

object EventFamilies {
  // User
  //todo(Andrew): please document
  val GENERIC_USER = UserEventFamily("")
  val SLIDER = UserEventFamily("slider")
  val SEARCH = UserEventFamily("search")
  val EXTENSION = UserEventFamily("extension")
  val ACCOUNT = UserEventFamily("account")
  val NOTIFICATION = UserEventFamily("notification")

  // Server
  val GENERIC_SERVER = ServerEventFamily("")
  val EXCEPTION = ServerEventFamily("exception")
  val DOMAIN_TAG_IMPORT = ServerEventFamily("domain_tag_import")
  val SERVER_SEARCH = ServerEventFamily("server_search")


  def apply(event: String): EventFamily = {
    event.toLowerCase.trim match {
      case SLIDER.name => SLIDER
      case SEARCH.name => SEARCH
      case EXTENSION.name => EXTENSION
      case ACCOUNT.name => ACCOUNT
      case NOTIFICATION.name => NOTIFICATION
      case EXCEPTION.name => EXCEPTION
      case SERVER_SEARCH.name => SERVER_SEARCH
      case s => throw new Exception("Unknown event family %s".format(s))
    }
  }
}

trait EventMetadata {
  val eventFamily: EventFamily
  val eventName: String
  val metaData: JsValue
  val prevEvents: Seq[ExternalId[Event]]
}

case class UserEventMetadata(eventFamily: EventFamily, eventName: String, userId: ExternalId[User], installId: String, userExperiments: Seq[State[ExperimentType]], metaData: JsObject, prevEvents: Seq[ExternalId[Event]]) extends EventMetadata
case class ServerEventMetadata(eventFamily: EventFamily, eventName: String, metaData: JsObject, prevEvents: Seq[ExternalId[Event]]) extends EventMetadata

case class Event(externalId: ExternalId[Event] = ExternalId[Event](), metaData: EventMetadata, createdAt: DateTime,
    serverVersion: String = inject[FortyTwoServices].currentService + ":" + inject[FortyTwoServices].currentVersion) {

  inject[EventHelper].newEvent(this)

  def persistToS3(): Event = {
    inject[S3EventStore] += (externalId -> this)
    this
  }
  def persistToMongo(): Event = inject[MongoEventStore].save(this)
}

object Events {
  def userEvent(eventFamily: EventFamily, eventName: String, user: User, experiments: Seq[State[ExperimentType]], installId: String, metaData: JsObject, prevEvents: Seq[ExternalId[Event]] = Nil, createdAt: DateTime = currentDateTime) =
    Event(metaData = UserEventMetadata(eventFamily, eventName, user.externalId, installId, experiments, metaData, prevEvents), createdAt = createdAt)

  def serverEvent(eventFamily: EventFamily, eventName: String, metaData: JsObject, prevEvents: Seq[ExternalId[Event]] = Nil, createdAt: DateTime = currentDateTime) =
    Event(metaData = ServerEventMetadata(eventFamily, eventName, metaData, prevEvents), createdAt = createdAt)
}
