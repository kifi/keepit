package com.keepit.heimdal

import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.serializer.{Companion, TypeCode}
import play.api.libs.functional.syntax._
import play.api.libs.json._


case class EventType(name: String)

object EventType {
  implicit val format = Json.format[EventType]
}


sealed trait HeimdalEvent {
  val context: HeimdalContext
  val eventType: EventType
  val time: DateTime
}

object HeimdalEvent {
  private val typeCodeMap = TypeCode.typeCodeMap[HeimdalEvent](UserEvent.typeCode, SystemEvent.typeCode)
  def getTypeCode(code: String) = typeCodeMap(code.toLowerCase)

  implicit val format = new Format[HeimdalEvent] {
    def writes(event: HeimdalEvent) = event match {
      case e: UserEvent => Companion.writes(e)
      case e: SystemEvent => Companion.writes(e)
    }
    private val readsFunc = Companion.reads(UserEvent, SystemEvent) // optimization
    def reads(json: JsValue) = readsFunc(json)
  }
}

case class UserEvent(
  userId: Long,
  context: HeimdalContext,
  eventType: EventType,
  time: DateTime = currentDateTime
) extends HeimdalEvent {
  override def toString(): String = s"UserEvent[user=$userId,type=${eventType.name},time=$time]"
}

object UserEvent extends Companion[UserEvent] {
  implicit val format = Json.format[UserEvent]
  implicit val typeCode = TypeCode("user")
}

case class SystemEvent(context: HeimdalContext, eventType: EventType, time: DateTime = currentDateTime) extends HeimdalEvent {
  override def toString(): String = s"SystemEvent[type=${eventType.name},time=$time]"
}

object SystemEvent extends Companion[SystemEvent] {
  implicit val format = Json.format[SystemEvent]
  implicit val typeCode = TypeCode("system")
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
