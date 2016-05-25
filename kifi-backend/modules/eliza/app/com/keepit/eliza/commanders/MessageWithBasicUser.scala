package com.keepit.eliza.commanders

import com.keepit.discussion.{ MessageSource, Message }
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.social.{ BasicUserLikeEntity }
import com.keepit.eliza.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class MessageWithBasicUser(
  id: BasicKeepEventId,
  createdAt: DateTime,
  text: String,
  source: Option[MessageSource],
  auxData: Option[JsArray],
  url: String,
  nUrl: String,
  user: BasicUserLikeEntity,
  participants: Seq[BasicUserLikeEntity])

object MessageWithBasicUser {
  implicit val basicUserLikeEntityFormat = BasicUserLikeEntity.format
  implicit val format = (
    (__ \ 'id).format[BasicKeepEventId] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'text).format[String] and
    (__ \ 'source).formatNullable[MessageSource] and
    (__ \ 'auxData).formatNullable[JsArray] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'user).format[BasicUserLikeEntity] and
    (__ \ 'participants).format[Seq[BasicUserLikeEntity]]
  )(MessageWithBasicUser.apply, unlift(MessageWithBasicUser.unapply))
}
