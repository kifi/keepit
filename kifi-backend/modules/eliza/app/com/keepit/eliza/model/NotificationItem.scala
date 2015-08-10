package com.keepit.eliza.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NKind, NotificationKind, NotificationEvent }
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
    groupIdentifier: Option[String] = None) extends Model[NotificationItem] {

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
    (__ \ "groupIdentifier").formatNullable[String]
  )(NotificationItem.applyFromDbRow, unlift(NotificationItem.unapplyToDbRow))

  def applyFromDbRow(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: String,
    event: NotificationEvent,
    groupIdentifier: Option[String]): NotificationItem =
    NotificationItem(
      id,
      createdAt,
      updatedAt,
      notificationId,
      NotificationKind.getByName(kind).get,
      event,
      groupIdentifier
    )

  def unapplyToDbRow(item: NotificationItem): Option[(Option[Id[NotificationItem]], DateTime, DateTime, Id[Notification], String, NotificationEvent, Option[String])] =
    Some(
      item.id,
      item.createdAt,
      item.updatedAt,
      item.notificationId,
      item.kind.name,
      item.event,
      item.groupIdentifier
    )

}
