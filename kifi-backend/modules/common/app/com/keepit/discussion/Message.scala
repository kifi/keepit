package com.keepit.discussion

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ PublicIdGenerator, ModelWithPublicId, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.store.ImagePath
import com.keepit.model.{ Hashtag, LibraryCardInfo, DeepLocator, Keep }
import com.keepit.social.{ BasicUser, BasicUserLikeEntity }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

// Exposed to clients
case class Message(
  pubId: PublicId[Message],
  sentAt: DateTime,
  sentBy: BasicUserLikeEntity,
  text: String)
object Message extends PublicIdGenerator[Message] {
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
  locator: DeepLocator,
  messages: Seq[Message])
object Discussion {
  implicit val format: Format[Discussion] = (
    (__ \ 'startedAt).format[DateTime] and
    (__ \ 'numMessages).format[Int] and
    (__ \ 'locator).format[DeepLocator] and
    (__ \ 'messages).format[Seq[Message]]
  )(Discussion.apply, unlift(Discussion.unapply))
}

// God forgive me, I'm creating yet another "_____-info" model
// May this be the start of a new convention where keeps have
//     `libraries: Seq[T]` instead of `library: T`
case class DiscussionKeep(
  id: PublicId[Keep],
  url: String,
  title: Option[String],
  note: Option[String],
  tags: Set[Hashtag],
  keptBy: BasicUser,
  keptAt: DateTime,
  imagePath: Option[ImagePath],
  libraries: Set[LibraryCardInfo])
object DiscussionKeep {
  private implicit val libCardFormat = LibraryCardInfo.internalFormat
  implicit val format = Json.format[DiscussionKeep]
}
