package com.keepit.discussion

import java.net.URLDecoder
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ PublicIdGenerator, PublicId }
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.json.EitherFormat
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

case class CrossServiceMessage(
  id: Id[Message],
  isDeleted: Boolean,
  seq: SequenceNumber[Message],
  keep: Id[Keep],
  sentAt: DateTime,
  sentBy: Option[Either[Id[User], BasicNonUser]],
  text: String,
  auxData: Option[KeepEvent],
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
    (__ \ 'auxData).formatNullable[KeepEvent] and
    (__ \ 'source).formatNullable[MessageSource](MessageSource.messageSourceFormat)
  )(CrossServiceMessage.apply, unlift(CrossServiceMessage.unapply))
}

case class CrossServiceKeepActivity( // a subset of messages + system messages on a keep
  numComments: Int, // # of non-system messages
  messages: Seq[CrossServiceMessage])
object CrossServiceKeepActivity {
  implicit val format: Format[CrossServiceKeepActivity] = (
    (__ \ 'numBasicMessages).format[Int] and
    (__ \ 'messages).format[Seq[CrossServiceMessage]]
  )(CrossServiceKeepActivity.apply, unlift(CrossServiceKeepActivity.unapply))
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

case class MessageSource(value: String)
object MessageSource {
  val CHROME = MessageSource("Chrome")
  val FIREFOX = MessageSource("Firefox")
  val SAFARI = MessageSource("Safari")
  val EMAIL = MessageSource("Email")
  val IPHONE = MessageSource("iPhone")
  val IPAD = MessageSource("iPad")
  val ANDROID = MessageSource("Android")
  val SERVER = MessageSource("server")
  val SITE = MessageSource("Kifi.com")
  val UNKNOWN = MessageSource("unknown")

  implicit val messageSourceFormat: Format[MessageSource] = Format(
    Reads { js => js.validate[String].map(MessageSource.apply) },
    Writes { o => JsString(o.value) }
  )
}
