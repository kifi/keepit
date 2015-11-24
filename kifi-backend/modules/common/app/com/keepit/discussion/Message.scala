package com.keepit.discussion

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.model.Keep
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Message(
  id: ExternalId[Message],
  sentAt: DateTime,
  sentBy: BasicUserLikeEntity,
  text: String)
object Message {
  implicit val format: Format[Message] = (
    (__ \ 'id).format[ExternalId[Message]] and
    (__ \ 'sentAt).format[DateTime] and
    (__ \ 'sentBy).format[BasicUserLikeEntity] and
    (__ \ 'text).format[String]
  )(Message.apply, unlift(Message.unapply))
}

case class Discussion(
  startedAt: DateTime,
  numMessages: Int,
  messages: Seq[Message])
object Discussion {
  implicit val format: Format[Discussion] = (
    (__ \ 'startedAt).format[DateTime] and
    (__ \ 'numMessages).format[Int] and
    (__ \ 'messages).format[Seq[Message]]
  )(Discussion.apply, unlift(Discussion.unapply))
}
