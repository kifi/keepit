package com.keepit.eliza.commanders

import org.joda.time.DateTime

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import com.keepit.eliza.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class MessageWithBasicUser(
  id: ExternalId[Message],
  createdAt: DateTime,
  text: String,
  source: Option[MessageSource],
  auxData: Option[JsArray],
  url: String,
  nUrl: String,
  user: Option[BasicUserLikeEntity],
  participants: Seq[BasicUserLikeEntity])

object MessageWithBasicUser {
  implicit val basicUserLikeEntityFormat = BasicUserLikeEntity.format
  implicit val format = (
    (__ \ 'id).format(ExternalId.format[Message]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'text).format[String] and
    (__ \ 'source).formatNullable[MessageSource] and
    (__ \ 'auxData).formatNullable[JsArray] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'user).formatNullable[BasicUserLikeEntity] and
    (__ \ 'participants).format[Seq[BasicUserLikeEntity]]
  )(MessageWithBasicUser.apply, unlift(MessageWithBasicUser.unapply))
}
