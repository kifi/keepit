package com.keepit.heimdal

import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.serializer.{Companion, TypeCode}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.reflection.CompanionTypeSystem

sealed trait HeimdalEvent { self =>
  type E >: self.type <: HeimdalEvent
  protected def companion: HeimdalEventCompanion[E]
  protected def instance: E = self
  val context: HeimdalContext
  val eventType: EventType
  val time: DateTime
}

sealed trait HeimdalEventCompanion[E <: HeimdalEvent] {
  def format: Format[E]
  def typeCode: String
  implicit def companion: HeimdalEventCompanion[E] = this
}

object HeimdalEvent {
  implicit val format = new Format[HeimdalEvent] {
    def writes(event: HeimdalEvent) = Json.obj("typeCode" -> event.companion.typeCode.toString, "value" -> event.companion.format.writes(event.instance))
    def reads(json: JsValue) = Reads.of[String].reads(json \ "typeCode").flatMap { typeCode => HeimdalEventCompanion.byTypecode(typeCode).format.reads(json \ "value") }
  }
}

object HeimdalEventCompanion {
  val all: Set[HeimdalEventCompanion[_ <: HeimdalEvent]] = CompanionTypeSystem[HeimdalEvent, HeimdalEventCompanion[_<: HeimdalEvent]]("E")
  val byTypecode: Map[String, HeimdalEventCompanion[_ <: HeimdalEvent]] = {
    require(all.size == all.map(_.typeCode).size, "Duplicate HeimdalEvent type codes.")
    all.map { vertexKind => vertexKind.typeCode -> vertexKind }.toMap
  }
}

case class UserEvent(
  userId: Id[User],
  context: HeimdalContext,
  eventType: EventType,
  time: DateTime = currentDateTime
) extends HeimdalEvent {
  type E = UserEvent
  def companion = UserEvent
  override def toString(): String = s"UserEvent[user=$userId,type=${eventType.name},time=$time]"
}

case object UserEvent extends HeimdalEventCompanion[UserEvent] {
  private implicit val idFormat = Id.format[User]
  implicit val format = Json.format[UserEvent]
  implicit val typeCode = "user"
}

case class SystemEvent(context: HeimdalContext, eventType: EventType, time: DateTime = currentDateTime) extends HeimdalEvent {
  type E = SystemEvent
  def companion = SystemEvent
  override def toString(): String = s"SystemEvent[type=${eventType.name},time=$time]"
}

case object SystemEvent extends HeimdalEventCompanion[SystemEvent] {
  implicit val format = Json.format[SystemEvent]
  implicit val typeCode = "system"
}

case class AnonymousEvent(context: HeimdalContext, eventType: EventType, time: DateTime = currentDateTime) extends HeimdalEvent {
  type E = AnonymousEvent
  def companion = AnonymousEvent
  override def toString(): String = s"AnonymousEvent[type=${eventType.name},time=$time]"
}

case object AnonymousEvent extends HeimdalEventCompanion[AnonymousEvent] {
  implicit val format = Json.format[AnonymousEvent]
  implicit val typeCode = "anonymous"
}

case class EventDescriptor(
  name: EventType,
  description: Option[String] = None,
  mixpanel: Boolean = false
)

object EventDescriptor {
  implicit val format: Format[EventDescriptor] = (
    (__ \ 'name).format[EventType] and
      (__ \ 'description).formatNullable[String] and
      (__ \ 'mixpanel).format[Boolean]
    )(EventDescriptor.apply, unlift(EventDescriptor.unapply))
}
