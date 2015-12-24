package com.keepit.eliza.commanders

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.discussion.Message
import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import com.keepit.eliza.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ElizaThreadInfo(
    threadId: MessageThreadId,
    participants: Seq[BasicUserLikeEntity],
    digest: String,
    lastAuthor: ExternalId[User],
    messageCount: Long,
    messageTimes: Map[PublicId[Message], DateTime],
    createdAt: DateTime,
    lastCommentedAt: DateTime,
    lastMessageRead: Option[DateTime],
    nUrl: Option[String],
    url: String,
    muted: Boolean) {
}

object ElizaThreadInfo {
  implicit def writesThreadInfo(implicit publicIdConfig: PublicIdConfiguration) = (
    (__ \ 'id).write(MessageThreadId.format) and
    (__ \ 'participants).write[Seq[BasicUserLikeEntity]] and
    (__ \ 'digest).write[String] and
    (__ \ 'lastAuthor).write(ExternalId.format[User]) and
    (__ \ 'messageCount).write[Long] and
    (__ \ 'messageTimes).write[JsObject].contramap { m: Map[PublicId[Message], DateTime] =>
      JsObject(m.toSeq.map { case (id, date) => id.id -> Json.toJson(date) })
    } and
    (__ \ 'createdAt).write[DateTime] and
    (__ \ 'lastCommentedAt).write[DateTime] and
    (__ \ 'lastMessageRead).writeNullable[DateTime] and
    (__ \ 'nUrl).writeNullable[String] and
    (__ \ 'url).write[String] and
    (__ \ 'muted).write[Boolean]
  )(unlift(ElizaThreadInfo.unapply))
}
