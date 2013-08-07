package com.keepit.eliza

import scala.concurrent.duration.Duration

import org.joda.time.DateTime

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class MessageWithBasicUser(
  id: ExternalId[Message],
  createdAt: DateTime,
  text: String,
  url: String,
  nUrl: String,
  user: Option[BasicUser],
  recipients: Seq[BasicUser]
)

object MessageWithBasicUser {
  implicit val format = (
    (__ \ 'id ).format(ExternalId.format[Message]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'text).format[String] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'user).formatNullable[BasicUser] and
    (__ \ 'recipients).format[Seq[BasicUser]]
  )(MessageWithBasicUser.apply, unlift(MessageWithBasicUser.unapply))
}
