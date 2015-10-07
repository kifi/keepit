package com.keepit.model

import play.api.libs.json._

case class MessageThreadId(id: Long) extends AnyVal
object MessageThreadId {
  implicit val format: Format[MessageThreadId] = Format(
    Reads { j => j.validate[Long].map(MessageThreadId(_)) },
    Writes { x => JsNumber(x.id) }
  )
}
case class KeepId(id: Long) extends AnyVal
case class UserThreadId(id: Long) extends AnyVal

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
