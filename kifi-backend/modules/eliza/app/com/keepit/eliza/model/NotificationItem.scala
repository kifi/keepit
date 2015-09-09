package com.keepit.eliza.model

import java.nio.ByteOrder
import java.util.UUID

import akka.util.ByteStringBuilder
import com.keepit.common.db.{ ModelWithExternalId, ExternalId, Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.notify.model.{ NotificationId, NKind, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class NotificationItem(
    id: Option[Id[NotificationItem]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: NKind,
    event: NotificationEvent,
    externalId: ExternalId[NotificationItem] = ExternalId(),
    eventTime: DateTime) extends ModelWithExternalId[NotificationItem] {

  override def withId(id: Id[NotificationItem]): NotificationItem = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationItem = copy(updatedAt = now)

}

object NotificationItem {

  implicit val format = (
    (__ \ "id").formatNullable[Id[NotificationItem]] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "updatedAt").format[DateTime] and
    (__ \ "notificationIdId").format[Id[Notification]] and
    (__ \ "kind").format[String] and
    (__ \ "event").format[NotificationEvent] and
    (__ \ "externalId").format[ExternalId[NotificationItem]] and
    (__ \ "eventTime").format[DateTime]
  )(NotificationItem.applyFromDbRow, unlift(NotificationItem.unapplyToDbRow))

  def applyFromDbRow(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: String,
    event: NotificationEvent,
    externalId: ExternalId[NotificationItem],
    eventTime: DateTime): NotificationItem =
    NotificationItem(
      id,
      createdAt,
      updatedAt,
      notificationId,
      NotificationKind.getByName(kind).get,
      event,
      externalId,
      eventTime
    )

  def unapplyToDbRow(item: NotificationItem): Option[(Option[Id[NotificationItem]], DateTime, DateTime, Id[Notification], String, NotificationEvent, ExternalId[NotificationItem], DateTime)] =
    Some(
      item.id,
      item.createdAt,
      item.updatedAt,
      item.notificationId,
      item.kind.name,
      item.event,
      item.externalId,
      item.eventTime
    )

}
