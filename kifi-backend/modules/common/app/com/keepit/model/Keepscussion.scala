package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ Id, ExternalId }
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class MessageThreadId(id: Long) extends AnyVal
object MessageThreadId {
  implicit val format: Format[MessageThreadId] = Format(
    Reads { j => j.validate[Long].map(MessageThreadId(_)) },
    Writes { id => JsNumber(id.id) }
  )
}
case class KeepId(id: Long) extends AnyVal
object KeepId {
  implicit val format: Format[KeepId] = Format(
    Reads { j => j.validate[Long].map(KeepId(_)) },
    Writes { id => JsNumber(id.id) }
  )
}

sealed abstract class KeepAsyncStatus(val value: String)
object KeepAsyncStatus {
  case object OKAY extends KeepAsyncStatus("okay")
  case object WAITING_FOR_MESSAGE_THREAD extends KeepAsyncStatus("waiting_for_message_thread")

  def apply(value: String): KeepAsyncStatus = value match {
    case OKAY.value => OKAY
    case WAITING_FOR_MESSAGE_THREAD.value => WAITING_FOR_MESSAGE_THREAD
  }

  implicit val format: Format[KeepAsyncStatus] = Format(
    Reads { j => j.validate[String].map(KeepAsyncStatus(_)) },
    Writes { s => JsString(s.value) }
  )
}

sealed abstract class MessageThreadAsyncStatus(val value: String)
object MessageThreadAsyncStatus {
  case object OKAY extends MessageThreadAsyncStatus("okay")
  case object WAITING_FOR_KEEP extends MessageThreadAsyncStatus("waiting_for_keep")

  def apply(value: String): MessageThreadAsyncStatus = value match {
    case OKAY.value => OKAY
    case WAITING_FOR_KEEP.value => WAITING_FOR_KEEP
  }

  implicit val format: Format[MessageThreadAsyncStatus] = Format(
    Reads { j => j.validate[String].map(MessageThreadAsyncStatus(_)) },
    Writes { s => JsString(s.value) }
  )
}

case class RawDiscussion(
  url: String,
  owner: ExternalId[User],
  users: Set[ExternalId[User]],
  libraries: Set[PublicId[Library]])
object RawDiscussion {
  implicit val format: Format[RawDiscussion] = (
    (__ \ 'url).format[String] and
    (__ \ 'owner).format[ExternalId[User]] and
    (__ \ 'users).format[Set[ExternalId[User]]] and
    (__ \ 'libraries).format[Set[PublicId[Library]]]
  )(RawDiscussion.apply, unlift(RawDiscussion.unapply))
}

case class Discussion(
  keepId: KeepId,
  uriId: Id[NormalizedURI],
  url: String,
  owner: Id[User],
  users: Set[Id[User]],
  libraries: Set[Id[Library]])

object Discussion {
  implicit val format: Format[Discussion] = (
    (__ \ 'keepId).format[KeepId] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'url).format[String] and
    (__ \ 'owner).format[Id[User]] and
    (__ \ 'users).format[Set[Id[User]]] and
    (__ \ 'libraries).format[Set[Id[Library]]]
  )(Discussion.apply, unlift(Discussion.unapply))
}

case class KeepAndMessageThread(keep: KeepId, messageThread: MessageThreadId)
object KeepAndMessageThread {
  implicit val format: Format[KeepAndMessageThread] = (
    (__ \ 'keep).format[KeepId] and
    (__ \ 'messageThread).format[MessageThreadId]
  )(KeepAndMessageThread.apply, unlift(KeepAndMessageThread.unapply))
}
