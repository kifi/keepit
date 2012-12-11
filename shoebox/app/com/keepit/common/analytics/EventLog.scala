package com.keepit.common.analytics

import java.sql.Connection
import org.joda.time.DateTime
import com.keepit.common.db.ExternalId
import com.keepit.common.db.State
import com.keepit.common.db.Id
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
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

case class UserEventMetadata(eventFamily: EventFamily, eventName: String, userId: ExternalId[User], installId: ExternalId[KifiInstallation], userExperiments: Seq[State[UserExperiment.ExperimentType]], metaData: JsValue) extends EventMetadata
case class ServerEventMetadata(eventFamily: EventFamily, eventName: String, metaData: JsValue) extends EventMetadata

case class Event(externalId: ExternalId[Event] = ExternalId[Event](), metaData: EventMetadata, createdAt: DateTime = currentDateTime, serverVersion: String = currentService + ":" + currentVersion) {

  def persistToS3() = {
    Event.S3Store += (externalId -> this)
  }
  def persistToMongo() = {
    inject[MongoEventStore].save(this)
  }
}

object Event {
  lazy val S3Store = inject[S3EventStore]
}

object Events {
  def userEvent(eventFamily: UserEventFamily, eventName: String, userId: Id[User], installId: ExternalId[KifiInstallation], metaData: JsObject)(implicit conn: Connection) = {
    val user = User.get(userId)
    val experiments = UserExperiment.getByUser(userId) map (_.experimentType)

    Event(metaData = UserEventMetadata(eventFamily, eventName, user.externalId, installId, experiments, metaData))
  }
  def serverEvent(eventFamily: ServerEventFamily, eventName: String, metaData: JsObject)(implicit conn: Connection) = {
    Event(metaData = ServerEventMetadata(eventFamily, eventName, metaData))
  }
}


class EventLog {

}