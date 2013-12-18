package com.keepit.eliza.commanders

import scala.concurrent.duration.Duration

import org.joda.time.DateTime

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.{BasicUserLikeEntity, BasicUser}
import com.keepit.eliza.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class MessageWithBasicUser(
  id: ExternalId[Message],
  createdAt: DateTime,
  text: String,
  auxData: Option[JsArray],
  url: String,
  nUrl: String,
  user: Option[BasicUser],
  participants: Seq[BasicUserLikeEntity]
)

object MessageWithBasicUser {
  implicit val basicUserLikeEntityFormat = BasicUserLikeEntity.basicUserLikeEntityFormat
  implicit val format = (
    (__ \ 'id ).format(ExternalId.format[Message]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'text).format[String] and
    (__ \ 'auxData).formatNullable[JsArray] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'user).formatNullable[BasicUser] and
    (__ \ 'participants).format[Seq[BasicUserLikeEntity]]
  )(MessageWithBasicUser.apply, unlift(MessageWithBasicUser.unapply))
}
