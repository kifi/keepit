package com.keepit.eliza

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ElizaThreadInfo(
    externalId: ExternalId[MessageThread],
    recipients: Seq[BasicUser],
    digest: String,
    lastAuthor: ExternalId[User],
    messageCount: Long,
    messageTimes: Map[ExternalId[Message], DateTime],
    createdAt: DateTime,
    lastCommentedAt: DateTime,
    lastMessageRead: Option[DateTime],
    nUrl: String)

object ElizaThreadInfo {
  implicit val writesThreadInfo = (
    (__ \ 'id).write(ExternalId.format[MessageThread]) and
    (__ \ 'recipients).write[Seq[BasicUser]] and
    (__ \ 'digest).write[String] and
    (__ \ 'lastAuthor).write(ExternalId.format[User]) and
    (__ \ 'messageCount).write[Long] and
    (__ \ 'messageTimes).write[JsObject].contramap { m: Map[ExternalId[Message], DateTime] =>
      JsObject(m.toSeq.map { case (id, date) => id.id -> Json.toJson(date)})
    } and
    (__ \ 'createdAt).write[DateTime] and
    (__ \ 'lastCommentedAt).write[DateTime] and
    (__ \ 'lastMessageRead).writeNullable[DateTime] and
    (__ \ 'nUrl).write[String] 
  )(unlift(ElizaThreadInfo.unapply))
}
