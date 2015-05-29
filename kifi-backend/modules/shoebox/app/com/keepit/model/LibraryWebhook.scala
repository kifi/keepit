package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.slick.lifted.MappedTo

case class LibraryWebhook(
    id: Option[Id[LibraryWebhook]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryWebhook] = State("active"),
    trigger: WebhookTrigger,
    libraryid: Id[Library],
    action: JsValue) extends ModelWithState[LibraryWebhook] {
  def withId(id: Id[LibraryWebhook]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[LibraryWebhook]) = this.copy(state = state)
  def isActive = this.state.value == "active"
}

object LibraryWebhook {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryWebhook]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[LibraryWebhook]] and
    (__ \ 'trigger).format[WebhookTrigger] and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'action).format[JsValue])(LibraryWebhook.apply _, unlift(LibraryWebhook.unapply _)) // json de/serialization
}

case class WebhookTrigger(value: String) extends MappedTo[String] {
  override def toString = value
}

object WebhookTrigger {
  val NEW_KEEP = WebhookTrigger("new_keep")

  implicit def format[T]: Format[WebhookTrigger] = Format(
    __.read[String].map(WebhookTrigger(_)),
    new Writes[WebhookTrigger] { def writes(o: WebhookTrigger) = JsString(o.value) }
  )

}