package com.keepit.eliza.model

import java.nio.ByteOrder
import java.util.UUID

import akka.util.ByteStringBuilder
import com.keepit.common.db.{ ExternalId, Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NotificationId, NKind, NotificationKind, NotificationEvent }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

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

  implicit val format = (
    (__ \ "id").formatNullable[Id[NotificationItem]] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "updatedAt").format[DateTime] and
    (__ \ "notificationIdId").format[Id[Notification]] and
    (__ \ "kind").format[String] and
    (__ \ "event").format[NotificationEvent]
  )(NotificationItem.applyFromDbRow, unlift(NotificationItem.unapplyToDbRow))

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

  /**
   * Conceptually an action consists of an unordered set of items.
   * However, to keep generating the same external id for a set of items, order matters.
   * This method imposes an ordering based on the id of the items, a fact that is supposed to never change.
   */
  private def ensureSame(items: Set[NotificationItem]): Seq[NotificationItem] = {
    items.toSeq.sortBy(_.id.get.id)
  }

  /**
   * Kifi clients detect new notifications based on the external id of what they are receiving.
   * This method is essentially a one-way function from a set of items to an external ID, to ensure that the
   * detection goes smoothly.
   */
  def externalIdFromItems(items: Set[NotificationItem]): NotificationId = {
    val sorted = ensureSame(items)
    val longList = sorted.map(_.id.get.id)

    implicit val byteOrder = ByteOrder.BIG_ENDIAN
    val builder = new ByteStringBuilder()
    for (longValue <- longList) {
      builder.putLong(longValue)
    }

    val byteArray = builder.result().toArray
    val uuid = UUID.nameUUIDFromBytes(byteArray)
    ExternalId(uuid.toString)
  }

}
