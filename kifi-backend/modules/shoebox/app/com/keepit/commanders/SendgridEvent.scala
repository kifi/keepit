package com.keepit.commanders

import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._

case class SendgridEvent(
  email: Option[String],
  sgEventId: Option[String],
  sgMessageId: Option[String],
  timestamp: Long,
  smtpId: Option[String],
  event: Option[String],
  category: Seq[String],
  id: Option[String],
  purchase: Option[String],
  uid: Option[String])

object SendgridEvent {
  implicit val sendgridEventReads = (
    (__ \ 'email).readNullable[String] and
    (__ \ 'sg_event_id).readNullable[String] and
    (__ \ 'sg_message_id).readNullable[String] and
    (__ \ 'timestamp).read[Long] and
    (__ \ "smtp-id").readNullable[String] and
    (__ \ 'event).readNullable[String] and
    (__ \ 'category).read[Seq[String]] and
    (__ \ 'id).readNullable[String] and
    (__ \ 'purchase).readNullable[String] and
    (__ \ 'uid).readNullable[String]
    )(SendgridEvent.apply _)
}
