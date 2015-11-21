package com.keepit.discussion

import com.keepit.common.db.Id
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Message(
  id: Id[Message],
  sentAt: DateTime,
  sentBy: BasicUserLikeEntity,
  text: String)
object Message {
  implicit val format: Format[Message] = (
    (__ \ 'id).format[Id[Message]] and
    (__ \ 'sentAt).format[DateTime] and
    (__ \ 'sentBy).format[BasicUserLikeEntity] and
    (__ \ 'text).format[String]
  )(Message.apply, unlift(Message.unapply))
}

