package com.keepit.discussion

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.Keep
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Message(
  pubId: PublicId[Message],
  sentAt: DateTime,
  sentBy: BasicUserLikeEntity,
  text: String)
    extends ModelWithPublicId[Message] {
  val id: Option[Id[Message]] = None
}
object Message extends ModelWithPublicIdCompanion[Message] {
  val publicIdIvSpec: IvParameterSpec = new IvParameterSpec(Array(-128, 93, 21, 18, 70, 113, -105, 79, -60, 109, -78, 108, -103, -82, 91, -14))
  val publicIdPrefix = "msg"
  implicit val format: Format[Message] = (
    (__ \ 'id).format[PublicId[Message]] and
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
