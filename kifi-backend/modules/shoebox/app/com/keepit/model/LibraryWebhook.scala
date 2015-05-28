package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class LibraryWebhook(
  id: Option[Id[LibraryWebhook]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[LibraryWebhook] = State("active"),
  libraryid: Id[Library],
  url: String,
  integration: String = "slack") // spec model

object LibraryWebhook {
  implicit def format = (
    (__ \ 'id).format[Option[Id[LibraryWebhook]]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[LibraryWebhook]] and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'url).format[String] and
    (__ \ 'integration).format[String])(LibraryWebhook.apply, unlift(LibraryWebhook.unapply)) // json de/serialization
}