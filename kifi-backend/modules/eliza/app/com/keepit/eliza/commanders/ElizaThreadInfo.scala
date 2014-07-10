package com.keepit.eliza.commanders

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import com.keepit.eliza.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ElizaThreadInfo(
  externalId: ExternalId[MessageThread],
  participants: Seq[BasicUserLikeEntity],
  digest: String,
  lastAuthor: ExternalId[User],
  messageCount: Long,
  messageTimes: Map[ExternalId[Message], DateTime],
  createdAt: DateTime,
  lastCommentedAt: DateTime,
  lastMessageRead: Option[DateTime],
  nUrl: Option[String],
  url: Option[String],
  muted: Boolean)

object ElizaThreadInfo {
  implicit val writesThreadInfo = (
    (__ \ 'id).write(ExternalId.format[MessageThread]) and
    (__ \ 'participants).write[Seq[BasicUserLikeEntity]] and
    (__ \ 'digest).write[String] and
    (__ \ 'lastAuthor).write(ExternalId.format[User]) and
    (__ \ 'messageCount).write[Long] and
    (__ \ 'messageTimes).write[JsObject].contramap { m: Map[ExternalId[Message], DateTime] =>
      JsObject(m.toSeq.map { case (id, date) => id.id -> Json.toJson(date) })
    } and
    (__ \ 'createdAt).write[DateTime] and
    (__ \ 'lastCommentedAt).write[DateTime] and
    (__ \ 'lastMessageRead).writeNullable[DateTime] and
    (__ \ 'nUrl).writeNullable[String] and
    (__ \ 'url).writeNullable[String] and
    (__ \ 'muted).write[Boolean]
  )(unlift(ElizaThreadInfo.unapply))
}
