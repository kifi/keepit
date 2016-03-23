package com.keepit.eliza.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model._
import com.keepit.model.Keep
import com.keepit.notify.delivery.WsNotificationDelivery
import com.keepit.notify.model.event.{LibraryNewKeep, NotificationEvent}
import com.keepit.notify.model.{NotificationKind, GroupingNotificationKind, NKind, Recipient}

import scala.concurrent.ExecutionContext

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    wsNotificationDelivery: WsNotificationDelivery,
    private implicit val publicIdConfiguration: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends Logging {

  def getItems(notification: Id[Notification]): Set[NotificationItem] = {
    db.readOnlyMaster { implicit session =>
      notificationItemRepo.getAllForNotification(notification)
    }.toSet
  }

  def setNotificationDisabledTo(notifId: Id[Notification], disabled: Boolean): Boolean = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.get(notifId)
      if (notif.disabled == disabled) {
        false
      } else {
        notificationRepo.save(notif.withDisabled(disabled))
        true
      }
    }
  }

  def setNotificationUnreadTo(notifId: Id[Notification], unread: Boolean): Boolean = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.get(notifId)
      if (notif.unread == unread) {
        false
      } else {
        notificationRepo.save(notif.withUnread(unread))
        true
      }
    }
  }
  private def shouldGroupWith(event: NotificationEvent, items: Set[NotificationItem]): Boolean = {
    val res = event.kind.shouldGroupWith(event, items.map(_.event).asInstanceOf[Set[event.N]])
    log.info(s"notif_debug for event $event with items $items, decided to group? $res")
    res
  }

  private def getGroupIdentifier(event: NotificationEvent): Option[String] = {
    Some(event.kind).collect {
      case kind: GroupingNotificationKind[event.N, _] => kind.gid.serialize(kind.getIdentifier(event))
    }
  }

  def processNewEvent(event: NotificationEvent): NotificationWithItems = {
    val notifWithItems = db.readWrite { implicit session =>
      val groupIdentifier = getGroupIdentifier(event)
      val existingNotifToGroupWith = groupIdentifier.map { identifier =>
        notificationRepo.getMostRecentByGroupIdentifier(event.recipient, event.kind, identifier)
      }.getOrElse {
        notificationRepo.getLastByRecipientAndKind(event.recipient, event.kind)
      }.filter { existingNotif =>
        val notifItems = notificationItemRepo.getAllForNotification(existingNotif.id.get)

        // God help me for this travesty
        notifItems.map(_.event) match {
          case Seq(oldEvent: LibraryNewKeep) => session.onTransactionSuccess {
            wsNotificationDelivery.delete(oldEvent.recipient, Keep.publicId(oldEvent.keepId).id)
          }
          case _ =>
        }

        shouldGroupWith(event, notifItems.toSet)
      }

      val notif = existingNotifToGroupWith.getOrElse(notificationRepo.save(Notification(
        recipient = event.recipient,
        kind = event.kind,
        groupIdentifier = groupIdentifier,
        lastEvent = event.time
      )))

      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event,
        eventTime = event.time
      ))

      NotificationWithItems(
        notification = notificationRepo.save(notif.copy(lastEvent = event.time)),
        items = notificationItemRepo.getAllForNotification(notif.id.get).toSet
      )
    }

    wsNotificationDelivery.deliver(event.recipient, notifWithItems)

    notifWithItems
  }

  def completeNotification(kind: NKind, groupIdentifier: String, recipient: Recipient): Boolean = {
    db.readWrite { implicit session =>
      notificationRepo.getMostRecentByGroupIdentifier(recipient, kind, groupIdentifier).fold(false) { notif =>
        notificationRepo.save(notif.copy(lastChecked = Some(notif.lastEvent)))
        true
      }
    }
  }

  def getUnreadNotificationsCount(recipient: Recipient): Int = db.readOnlyMaster { implicit session =>
    notificationRepo.getUnreadNotificationsCount(recipient)
  }

}
