package com.keepit.notify.action

import java.nio.ByteOrder
import java.util.UUID

import akka.util.ByteStringBuilder
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.path.Path
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.model.Library

/**
 * Represents what the user eventually sees, as a new notification in their inbox.
 */
trait NotificationAction {

  val items: Set[NotificationItem]
  val notificationId: ExternalId[Notification]
  val path: Path
  val imageUrl: String
  val body: String
  val hoverText: String

  lazy val id: ExternalId[NotificationAction] = NotificationAction.externalIdFromItems(items)

}

object NotificationAction {

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
  def externalIdFromItems(items: Set[NotificationItem]): ExternalId[NotificationAction] = {
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

case class ViewLibrary(
  library: Id[Library],
  items: Set[NotificationItem],
  notificationId: ExternalId[Notification],
  path: Path,
  imageUrl: String,
  body: String,
  hoverText: String) extends NotificationAction
