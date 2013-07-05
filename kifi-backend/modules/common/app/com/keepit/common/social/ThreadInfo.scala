package com.keepit.common.social

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ThreadInfo(
    externalId: ExternalId[Comment],
    recipients: Seq[BasicUser],
    digest: String,
    lastAuthor: ExternalId[User],
    messageCount: Long,
    messageTimes: Map[ExternalId[Comment], DateTime],
    createdAt: DateTime,
    lastCommentedAt: DateTime)

object ThreadInfo {
  implicit val writesThreadInfo = (
    (__ \ 'id).write(ExternalId.format[Comment]) and
    (__ \ 'recipients).write[Seq[BasicUser]] and
    (__ \ 'digest).write[String] and
    (__ \ 'lastAuthor).write(ExternalId.format[User]) and
    (__ \ 'messageCount).write[Long] and
    (__ \ 'messageTimes).write[JsObject].contramap { m: Map[ExternalId[Comment], DateTime] =>
      JsObject(m.toSeq.map { case (id, date) => id.id -> Json.toJson(date)})
    } and
    (__ \ 'createdAt).write[DateTime] and
    (__ \ 'lastCommentedAt).write[DateTime]
  )(unlift(ThreadInfo.unapply))
}
