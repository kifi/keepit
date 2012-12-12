package com.keepit.common.analytics

import java.sql.Connection
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.ExternalId
import com.keepit.common.db.State
import com.keepit.common.db.Id
import com.keepit.inject.inject
import com.keepit.model.KifiInstallation
import com.keepit.model.User
import com.keepit.model.UserExperiment
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices._

import play.api.Play.current
import play.api.libs.json._

trait EventFamily {
  val name: String
  override def toString = name
}

case class UserEventFamily(name: String) extends EventFamily
case class ServerEventFamily(name: String) extends EventFamily

object EventFamilies {
  // User
  val SLIDER = UserEventFamily("slider")
  val SEARCH = UserEventFamily("search")
  val SERVER = ServerEventFamily("server")

  def apply(event: String): EventFamily = {
    event.toLowerCase.trim match {
      case SLIDER.name => SLIDER
      case SEARCH.name => SEARCH
      case SERVER.name => SERVER
      case s => throw new Exception("Unknown event family %s".format(s))
    }
  }
}

trait EventMetadata {
  val eventName: String
  val metaData: JsValue
  val eventFamily: EventFamily
}

case class UserEventMetadata(eventFamily: EventFamily, eventName: String, userId: ExternalId[User], installId: ExternalId[KifiInstallation], userExperiments: Seq[State[UserExperiment.ExperimentType]], metaData: JsValue, prevEvents: Seq[ExternalId[Event]]) extends EventMetadata
case class ServerEventMetadata(eventFamily: EventFamily, eventName: String, metaData: JsValue, prevEvents: Seq[ExternalId[Event]]) extends EventMetadata

case class Event(externalId: ExternalId[Event] = ExternalId[Event](), metaData: EventMetadata, createdAt: DateTime, serverVersion: String = currentService + ":" + currentVersion) {

  def persistToS3() = {
    inject[S3EventStore] += (externalId -> this)
  }
  def persistToMongo() = {
    inject[MongoEventStore].save(this)
  }
  def persist() = {
    persistToS3()
    persistToMongo()
  }
}

object Event {
}

object Events {
  def userEvent(eventFamily: EventFamily, eventName: String, userId: Id[User], installId: ExternalId[KifiInstallation], metaData: JsObject, prevEvents: Seq[ExternalId[Event]] = Nil, createdAt: DateTime = currentDateTime)(implicit conn: Connection) = {
    val user = User.get(userId)
    val experiments = UserExperiment.getByUser(userId) map (_.experimentType)

    Event(metaData = UserEventMetadata(eventFamily, eventName, user.externalId, installId, experiments, metaData, prevEvents), createdAt = createdAt)
  }
  def serverEvent(eventFamily: EventFamily, eventName: String, metaData: JsObject, prevEvents: Seq[ExternalId[Event]] = Nil, createdAt: DateTime = currentDateTime)(implicit conn: Connection) = {
    Event(metaData = ServerEventMetadata(eventFamily, eventName, metaData, prevEvents), createdAt = createdAt)
  }
}


class EventLog {

}