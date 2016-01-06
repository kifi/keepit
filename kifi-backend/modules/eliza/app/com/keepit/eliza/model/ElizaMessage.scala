package com.keepit.eliza.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.notify.model.Recipient
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ Keep, User, NormalizedURI }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key }
import scala.concurrent.duration.Duration
import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed trait MessageSender {
  def isSystem: Boolean = false
  def asUser: Option[Id[User]] = None
  def asNonUser: Option[NonUserParticipant] = None

  def fold[T](systemF: => T, userF: Id[User] => T, nonUserF: NonUserParticipant => T): T = {
    asUser.map(userF).
      orElse { asNonUser.map(nup => nonUserF(nup)) }.
      getOrElse { systemF }
  }

  def asRecipient: Option[Recipient] =
    if (isSystem) None
    else asUser.map(userId => Recipient(userId)) orElse asNonUser.collect {
      case NonUserEmailParticipant(email) => Recipient(email)
    }
}

object MessageSender {
  implicit val format: Format[MessageSender] = new Format[MessageSender] {

    def reads(json: JsValue) = {
      val kind: String = (json \ "kind").as[String]
      kind match {
        case "user" => JsSuccess(User(Id[com.keepit.model.User]((json \ "id").as[Long])))
        case "nonUser" => JsSuccess(NonUser((json \ "nup").as[NonUserParticipant]))
        case "system" => JsSuccess(System)
        case _ => JsError(kind)
      }
    }

    def writes(obj: MessageSender) = obj match {
      case User(id) => Json.obj(
        "kind" -> "user",
        "id" -> id.id
      )
      case NonUser(nup) => Json.obj(
        "kind" -> "nonUser",
        "nup" -> Json.toJson(nup)
      )
      case System => Json.obj(
        "kind" -> "system"
      )
    }

  }

  case class User(id: Id[com.keepit.model.User]) extends MessageSender {
    override def asUser = Some(id)
  }

  case class NonUser(nup: NonUserParticipant) extends MessageSender {
    override def asNonUser = Some(nup)
  }

  case object System extends MessageSender {
    override def isSystem: Boolean = true
  }

  def toMessageSenderView(messageSender: MessageSender): MessageSenderView =
    messageSender.fold(MessageSenderSystemView(), MessageSenderUserView, nup => MessageSenderNonUserView(nup.identifier))
}

case class MessageSource(value: String)
object MessageSource {
  val CHROME = MessageSource("Chrome")
  val FIREFOX = MessageSource("Firefox")
  val EMAIL = MessageSource("Email")
  val IPHONE = MessageSource("iPhone")
  val IPAD = MessageSource("iPad")
  val ANDROID = MessageSource("Android")
  val SERVER = MessageSource("server")
  val SITE = MessageSource("Kifi.com")

  implicit val messageSourceFormat = new Format[MessageSource] {
    def reads(json: JsValue): JsResult[MessageSource] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(MessageSource(str))
        case None => JsError()
      }
    }
    def writes(kind: MessageSource): JsValue = {
      JsString(kind.value)
    }
  }
}

case class ElizaMessage(
  id: Option[Id[ElizaMessage]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ElizaMessage] = ElizaMessageStates.ACTIVE,
  seq: SequenceNumber[ElizaMessage] = SequenceNumber.ZERO,
  keepId: Id[Keep],
  from: MessageSender,
  messageText: String,
  source: Option[MessageSource],
  auxData: Option[JsArray] = None,
  sentOnUrl: Option[String],
  sentOnUriId: Option[Id[NormalizedURI]])
    extends Model[ElizaMessage] with ModelWithState[ElizaMessage] with ModelWithSeqNumber[ElizaMessage] {
  def withId(id: Id[ElizaMessage]): ElizaMessage = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withText(newText: String) = this.copy(messageText = newText)
  def sanitizeForDelete = this.copy(state = ElizaMessageStates.INACTIVE)

  def pubId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Message] = Message.publicId(ElizaMessage.toCommonId(id.get))
  def pubKeepId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Keep] = Keep.publicId(keepId)

  def isActive: Boolean = state == ElizaMessageStates.ACTIVE
}
object ElizaMessageStates extends States[ElizaMessage]

object ElizaMessage extends CommonClassLinker[ElizaMessage, Message] {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ElizaMessage]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[ElizaMessage]] and
    (__ \ 'seq).format[SequenceNumber[ElizaMessage]] and
    (__ \ 'keepId).format[Id[Keep]] and
    (__ \ 'from).format[MessageSender] and
    (__ \ 'messageText).format[String] and
    (__ \ 'source).formatNullable[MessageSource] and
    (__ \ 'auxData).formatNullable[JsArray] and
    (__ \ 'sentOnUrl).formatNullable[String] and
    (__ \ 'sentOnUriId).formatNullable(Id.format[NormalizedURI])
  )(ElizaMessage.apply, unlift(ElizaMessage.unapply))

  def fromDbRow(
    id: Option[Id[ElizaMessage]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[ElizaMessage],
    seq: SequenceNumber[ElizaMessage],
    keepId: Id[Keep],
    userSender: Option[Id[User]],
    messageText: String,
    source: Option[MessageSource],
    auxData: Option[JsArray],
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]],
    nonUserSender: Option[JsValue]): ElizaMessage = {
    ElizaMessage(
      id,
      createdAt,
      updatedAt,
      state,
      seq,
      keepId,
      userSender.map(MessageSender.User(_)).getOrElse(nonUserSender.map(json => MessageSender.NonUser(json.as[NonUserParticipant])).getOrElse(MessageSender.System)),
      messageText,
      source,
      auxData,
      sentOnUrl,
      sentOnUriId
    )
  }

  def toDbRow(message: ElizaMessage): Option[(Option[Id[ElizaMessage]], DateTime, DateTime, State[ElizaMessage], SequenceNumber[ElizaMessage], Id[Keep], Option[Id[User]], String, Option[MessageSource], Option[JsArray], Option[String], Option[Id[NormalizedURI]], Option[JsValue])] = {
    Some((
      message.id,
      message.createdAt,
      message.updatedAt,
      message.state,
      message.seq,
      message.keepId,
      message.from.asUser,
      message.messageText,
      message.source,
      message.auxData,
      message.sentOnUrl,
      message.sentOnUriId,
      message.from.asNonUser.map(Json.toJson(_))
    ))
  }

  def toMessageView(message: ElizaMessage): MessageView = {
    MessageView(
      from = MessageSender.toMessageSenderView(message.from),
      messageText = message.messageText,
      createdAt = message.createdAt)
  }

  def toCrossServiceMessage(message: ElizaMessage): CrossServiceMessage = {
    CrossServiceMessage(
      id = ElizaMessage.toCommonId(message.id.get),
      seq = ElizaMessage.toCommonSeq(message.seq),
      keep = message.keepId,
      sentAt = message.createdAt,
      sentBy = message.from.asUser,
      text = message.messageText
    )
  }
}

case class MessagesKeepIdKey(keepId: Id[Keep]) extends Key[Seq[ElizaMessage]] {
  override val version = 1
  val namespace = "messages_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class MessagesByKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessagesKeepIdKey, Seq[ElizaMessage]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

