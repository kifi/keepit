package com.keepit.eliza.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.notify.model.{ NKind, NotificationKind, NotificationEvent }
import org.joda.time.DateTime

case class NotificationItem(
    id: Option[Id[NotificationItem]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: NKind,
    event: NotificationEvent) extends Model[NotificationItem] {

  override def withId(id: Id[NotificationItem]): NotificationItem = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationItem = copy(updatedAt = now)

}

object NotificationItem {

  def applyFromDbRow(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: String,
    event: NotificationEvent): NotificationItem =
    NotificationItem(
      id,
      createdAt,
      updatedAt,
      notificationId,
      NotificationKind.getByName(kind).get,
      event
    )

  def unapplyToDbRow(item: NotificationItem): Option[(Option[Id[NotificationItem]], DateTime, DateTime, Id[Notification], String, NotificationEvent)] =
    Some(
      item.id,
      item.createdAt,
      item.updatedAt,
      item.notificationId,
      item.kind.name,
      item.event
    )

}
