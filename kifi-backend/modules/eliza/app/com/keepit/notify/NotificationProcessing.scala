package com.keepit.notify

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ NotificationItemRepo, NotificationRepo, Notification, NotificationItem }
import com.keepit.notify.delivery.WsNotificationDelivery
import com.keepit.notify.info.NotificationInfo
import com.keepit.notify.model.NotificationKind
import com.keepit.notify.model.event.NotificationEvent

import scala.concurrent.ExecutionContext

class NotificationProcessing @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    delivery: WsNotificationDelivery,
    notifExperimentCheck: NotifExperimentCheck,
    implicit val executionContext: ExecutionContext) extends Logging {

  private def shouldGroupWith(event: NotificationEvent, items: Set[NotificationItem]): Boolean = {
    val kind = event.kind.asInstanceOf[NotificationKind[NotificationEvent]]
    val res = kind.shouldGroupWith(event, items.map(_.event))
    log.info(s"notif_debug for event $event with items $items, decided to group? $res")
    res
  }

  private def getGroupIdentifier(event: NotificationEvent): Option[String] = {
    event.kind.asInstanceOf[NotificationKind[NotificationEvent]].groupIdentifier(event)
  }

  private def saveToExistingNotification(notifId: Id[Notification], event: NotificationEvent): Notification = {
    db.readWrite { implicit session =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notifId,
        kind = event.kind,
        event = event
      ))
      val notif = notificationRepo.get(notifId)
      notificationRepo.save(notif.copy(lastEvent = event.time))
    }
  }

  private def createNewNotification(event: NotificationEvent): Notification = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.save(Notification(
        recipient = event.recipient,
        kind = event.kind,
        lastEvent = event.time
      ))
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event
      ))
      notif
    }
  }

  def processNewEvent(event: NotificationEvent, info: Option[NotificationInfo]): Notification = {
    val groupIdentifier = getGroupIdentifier(event)
    val notif = groupIdentifier match {
      case Some(identifier) =>
        db.readOnlyMaster { implicit session =>
          notificationRepo.getByKindAndGroupIdentifier(event.kind, identifier)
        } match {
          case Some(notif) => saveToExistingNotification(notif.id.get, event)
          case None => createNewNotification(event)
        }
      case None => {
        db.readOnlyMaster { implicit session =>
          notificationRepo.getLastByRecipientAndKind(event.recipient, event.kind)
        } match {
          case Some(existingNotif) =>
            val notifItems = db.readOnlyMaster { implicit session =>
              notificationItemRepo.getAllForNotification(existingNotif.id.get)
            }
            if (shouldGroupWith(event, notifItems.toSet)) {
              saveToExistingNotification(existingNotif.id.get, event)
            } else {
              createNewNotification(event)
            }
          case _ => createNewNotification(event)
        }
      }
    }
    val items = db.readOnlyMaster { implicit session =>
      notificationItemRepo.getAllForNotification(notif.id.get)
    }.toSet
    notifExperimentCheck.ifExperiment(notif.recipient) { recipient =>
      delivery.deliver(recipient, items)
    }
    notif
  }

}
