package com.keepit.commanders

import com.keepit.common.db.ExternalId
import com.keepit.common.mail.ElectronicMail
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

@json case class SendgridEventType(name: String) extends AnyVal

case class SendgridEvent(
  email: Option[String],
  mailId: Option[ExternalId[ElectronicMail]],
  timestamp: DateTime,
  reason: Option[String],
  smtpId: Option[String],
  event: Option[SendgridEventType],
  id: Option[String],
  useragent: Option[String],
  response: Option[String],
  url: Option[String])

object SendgridEvent {
  implicit val sendgridEventReads = (
    (__ \ 'email).readNullable[String] and
    (__ \ 'mail_id).readNullable[ExternalId[ElectronicMail]] and
    (__ \ 'timestamp).read[DateTime] and
    (__ \ 'reason).readNullable[String] and
    (__ \ "smtp-id").readNullable[String] and
    (__ \ 'event).readNullable[SendgridEventType] and
    (__ \ 'id).readNullable[String] and
    (__ \ 'useragent).readNullable[String] and
    (__ \ 'response).readNullable[String] and
    (__ \ 'url).readNullable[String]
  )(SendgridEvent.apply _)
}

object SendgridEventTypes {
  val CLICK = SendgridEventType("click")
  val UNSUBSCRIBE = SendgridEventType("unsubscribe")
  val BOUNCE = SendgridEventType("bounce")
  val SPAM_REPORT = SendgridEventType("spamreport")
  val DELIVERED = SendgridEventType("delivered")
}

