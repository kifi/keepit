package com.keepit.discussion

import java.net.URLDecoder
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ PublicIdGenerator, PublicId }
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.json.{ SchemaReads, EnumFormat, EitherFormat }
import com.keepit.common.net.UserAgent
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.ImagePath
import com.keepit.model._
import com.keepit.common.core.regexExtensionOps
import com.keepit.social.{ BasicNonUser, BasicUser, BasicUserLikeEntity }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Try

// Exposed to clients
case class Message(
  pubId: PublicId[Message],
  sentAt: DateTime,
  sentBy: BasicUserLikeEntity,
  text: String,
  source: Option[MessageSource])
object Message extends PublicIdGenerator[Message] {
  val publicIdIvSpec: IvParameterSpec = new IvParameterSpec(Array(-128, 93, 21, 18, 70, 113, -105, 79, -60, 109, -78, 108, -103, -82, 91, -14))
  val publicIdPrefix = "msg"
  implicit val format: Format[Message] = (
    (__ \ 'id).format[PublicId[Message]] and
    (__ \ 'sentAt).format[DateTime] and
    (__ \ 'sentBy).format[BasicUserLikeEntity] and
    (__ \ 'text).format[String] and
    (__ \ 'source).formatNullable[MessageSource]
  )(Message.apply, unlift(Message.unapply))
}

case class CrossServiceMessage(
  id: Id[Message],
  isDeleted: Boolean,
  seq: SequenceNumber[Message],
  keep: Id[Keep],
  sentAt: DateTime,
  sentBy: Option[Either[Id[User], BasicNonUser]],
  text: String,
  auxData: Option[KeepEventData],
  source: Option[MessageSource])
object CrossServiceMessage {
  private val lookHereRe = """\[([^\]\\]*(?:\\[\]\\][^\]\\]*)*)\]\(x-kifi-sel:([^\)\\]*(?:\\[\)\\][^\)\\]*)*)\)""".r
  def stripLookHeresToPointerText(str: String): String = lookHereRe.replaceAllIn(str, _.group(1))
  def stripLookHeresToReferencedText(str: String): String = lookHereRe.replaceAllIn(str, m => Try(URLDecoder.decode(m.group(2).split('|').last, "UTF-8")).getOrElse("look here"))
  def splitOutLookHeres(str: String): Seq[Either[String, Try[(String, String)]]] = lookHereRe.findMatchesAndInterstitials(str).map {
    case Left(text) => Left(text)
    case Right(m) => Right(Try(m.group(1) -> URLDecoder.decode(m.group(2).split('|').last, "UTF-8")))
  }

  implicit val sentByFormat = EitherFormat(Id.format[User], BasicNonUser.format)
  implicit val format: Format[CrossServiceMessage] = (
    (__ \ 'id).format[Id[Message]] and
    (__ \ 'isDeleted).format[Boolean] and
    (__ \ 'seq).format[SequenceNumber[Message]] and
    (__ \ 'keep).format[Id[Keep]] and
    (__ \ 'sentAt).format[DateTime] and
    (__ \ 'sentBy).formatNullable[Either[Id[User], BasicNonUser]] and
    (__ \ 'text).format[String] and
    (__ \ 'auxData).formatNullable[KeepEventData] and
    (__ \ 'source).formatNullable[MessageSource](MessageSource.messageSourceFormat)
  )(CrossServiceMessage.apply, unlift(CrossServiceMessage.unapply))
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

case class CrossServiceDiscussion(
  startedAt: DateTime,
  numMessages: Int,
  locator: DeepLocator,
  messages: Seq[CrossServiceMessage])
object CrossServiceDiscussion {
  implicit val format: Format[CrossServiceDiscussion] = (
    (__ \ 'startedAt).format[DateTime] and
    (__ \ 'numMessages).format[Int] and
    (__ \ 'locator).format[DeepLocator] and
    (__ \ 'messages).format[Seq[CrossServiceMessage]]
  )(CrossServiceDiscussion.apply, unlift(CrossServiceDiscussion.unapply))
}

// God forgive me, I'm creating yet another "_____-info" model
// May this be the start of a new convention where keeps have
//     `libraries: Seq[T]` instead of `library: T`
case class DiscussionKeep(
  id: PublicId[Keep],
  url: String,
  path: String,
  title: Option[String],
  note: Option[String],
  tags: Set[Hashtag],
  keptBy: Option[BasicUser],
  keptAt: DateTime,
  imagePath: Option[ImagePath],
  libraries: Set[LibraryCardInfo],
  permissions: Set[KeepPermission])
object DiscussionKeep {
  private implicit val libCardFormat = LibraryCardInfo.internalFormat
  implicit val format = Json.format[DiscussionKeep]
}

sealed abstract class MessageSource(val value: String)
object MessageSource extends Enumerator[MessageSource] {
  case object CHROME extends MessageSource("Chrome")
  case object FIREFOX extends MessageSource("Firefox")
  case object SAFARI extends MessageSource("Safari")
  case object EMAIL extends MessageSource("Email")
  case object IPHONE extends MessageSource("iPhone")
  case object ANDROID extends MessageSource("Android")
  case object SITE extends MessageSource("Kifi.com")

  val all = _all
  def fromStr(str: String) = all.find(_.value == str)
  def apply(str: String) = fromStr(str).get

  implicit val messageSourceFormat: Format[MessageSource] = EnumFormat.format(fromStr, _.value)
  implicit val schemaReads: SchemaReads[MessageSource] = SchemaReads.trivial("message_source")
}
