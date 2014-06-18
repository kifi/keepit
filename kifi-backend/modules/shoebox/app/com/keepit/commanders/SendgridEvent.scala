package com.keepit.commanders

import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.{EmailAddress, ElectronicMail}
import org.joda.time.DateTime
import com.keepit.common.time._

case class SendgridEvent(
  email: Option[EmailAddress],
  mailId: Option[ExternalId[ElectronicMail]],
  timestamp: DateTime,
  reason: Option[String],
  smtpId: Option[String],
  event: Option[String],
  id: Option[String],
  useragent: Option[String],
  response: Option[String],
  url: Option[String]
)

object SendgridEvent {
  implicit val sendgridEventReads = (
    (__ \ 'email).readNullable[EmailAddress] and
    (__ \ 'mail_id).readNullable[ExternalId[ElectronicMail]] and
    (__ \ 'timestamp).read[DateTime] and
    (__ \ 'reason).readNullable[String] and
    (__ \ "smtp-id").readNullable[String] and
    (__ \ 'event).readNullable[String] and
    (__ \ 'id).readNullable[String] and
    (__ \ 'useragent).readNullable[String] and
    (__ \ 'response).readNullable[String] and
    (__ \ 'url).readNullable[String]
  )(SendgridEvent.apply _)
}
