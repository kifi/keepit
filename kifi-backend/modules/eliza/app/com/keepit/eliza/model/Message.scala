package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ModelWithState, ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import MessagingTypeMappers._
import com.keepit.common.logging.Logging
import com.keepit.common.cache.{CacheSizeLimitExceededException, JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration
import play.api.libs.json._
import play.api.libs.json.util._
import play.api.libs.functional.syntax._
import scala.slick.lifted.Query
import scala.slick.jdbc.StaticQuery


sealed trait MessageSender {
  def isSystem: Boolean = false
  def asUser: Option[Id[User]] = None
  def asNonUser: Option[NonUserParticipant] = None
}

object MessageSender {
  implicit val format : Format[MessageSender] = new Format[MessageSender] {

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
}

case class Message(
    id: Option[Id[Message]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Message] = ExternalId(),
    from: MessageSender,
    thread: Id[MessageThread],
    threadExtId: ExternalId[MessageThread],
    messageText: String,
    auxData: Option[JsArray] = None,
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]]
  )
  extends ModelWithExternalId[Message] {

  def withId(id: Id[Message]): Message = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt=updateTime)
}

object Message {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[Message]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'externalId).format(ExternalId.format[Message]) and
      (__ \ 'from).format[MessageSender] and
      (__ \ 'thread).format(Id.format[MessageThread]) and
      (__ \ 'threadExtId).format(ExternalId.format[MessageThread]) and
      (__ \ 'messageText).format[String] and
      (__ \ 'auxData).formatNullable[JsArray] and
      (__ \ 'sentOnUrl).formatNullable[String] and
      (__ \ 'sentOnUriId).formatNullable(Id.format[NormalizedURI])
    )(Message.apply, unlift(Message.unapply))

  def fromDbTuple(
    id: Option[Id[Message]],
    createdAt: DateTime,
    updatedAt: DateTime,
    externalId: ExternalId[Message],
    userSender: Option[Id[User]],
    thread: Id[MessageThread],
    threadExtId: ExternalId[MessageThread],
    messageText: String,
    auxData: Option[JsArray],
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]],
    nonUserSender: Option[JsValue]
  ): Message = {
    Message(
      id,
      createdAt,
      updatedAt,
      externalId,
      userSender.map(MessageSender.User(_)).getOrElse(nonUserSender.map(json => MessageSender.NonUser(json.as[NonUserParticipant])).getOrElse(MessageSender.System)),
      thread,
      threadExtId,
      messageText,
      auxData,
      sentOnUrl,
      sentOnUriId
    )
  }

  def toDbTuple(message: Message): Option[(Option[Id[Message]], DateTime, DateTime, ExternalId[Message], Option[Id[User]], Id[MessageThread], ExternalId[MessageThread], String,Option[JsArray],Option[String], Option[Id[NormalizedURI]], Option[JsValue])] = {
    Some((
      message.id,
      message.createdAt,
      message.updatedAt,
      message.externalId,
      message.from.asUser,
      message.thread,
      message.threadExtId,
      message.messageText,
      message.auxData,
      message.sentOnUrl,
      message.sentOnUriId,
      message.from.asNonUser.map(Json.toJson(_))
    ))
  }
}

case class MessagesForThread(val thread:Id[MessageThread], val messages:Seq[Message])
{
  override def equals(other:Any):Boolean = other match {
    case mft: MessagesForThread => (thread.id == mft.thread.id && messages.size == mft.messages.size)
    case _ => false
  }
  override def hashCode = thread.id.hashCode
  override def toString = "[MessagesForThread(%s): %s]".format(thread, messages)
}

object MessagesForThread {

  implicit val messagesForThreadReads = (
    (__ \ 'thread_id).read(Id.format[MessageThread]) and
    (__ \ 'messages).read[Seq[Message]]
  )(MessagesForThread.apply _)

  implicit val messagesForThreadWrites = (
    (__ \ 'thread_id).write(Id.format[MessageThread]) and
    (__ \ 'messages).write(Writes.traversableWrites[Message])
  )(unlift(MessagesForThread.unapply))

}

case class MessagesForThreadIdKey(threadId:Id[MessageThread]) extends Key[MessagesForThread] {
  override val version = 4
  val namespace = "messages_for_thread_id"
  def toKey():String = threadId.id.toString
}

class MessagesForThreadIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessagesForThreadIdKey, MessagesForThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

