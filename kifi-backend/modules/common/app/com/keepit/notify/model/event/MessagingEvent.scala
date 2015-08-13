package com.keepit.notify.model.event

import com.keepit.notify.model.{NotificationKind, Recipient, NotificationEvent}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait MessagingEvent extends NotificationEvent

case class NewMessage(
  recipient: Recipient,
  time: DateTime,
  messageThreadId: Long, // need to use long here because MessageThread is only defined in Eliza
  messageId: Long // same here
) extends MessagingEvent {

  val kind = NewMessage

}

object NewMessage extends NotificationKind[NewMessage] {

  override val name: String = "new_message"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "messageThreadId").format[Long] and
      (__ \ "messageId").format[Long]
    )(NewMessage.apply, unlift(NewMessage.unapply))

  override def groupIdentifier(event: NewMessage): Option[String] = Some(event.messageThreadId.toString)

  override def shouldGroupWith(newEvent: NewMessage, existingEvents: Set[NewMessage]): Boolean = {
    val existing = existingEvents.head
    existing.messageThreadId == newEvent.messageThreadId
  }

}
