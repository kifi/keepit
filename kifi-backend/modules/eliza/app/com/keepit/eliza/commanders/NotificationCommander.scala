package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.eliza.model.{ Notification, NotificationItem, NotificationItemRepo, NotificationRepo }
import com.keepit.notify.model.{ NotificationKind, NotificationEvent }

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo) {

  private def shouldGroupWith(event: NotificationEvent, items: Set[NotificationItem]): Boolean = {
    val kind = event.kind.asInstanceOf[NotificationKind[NotificationEvent]]
    kind.shouldGroupWith(event, items.map(_.event))
  }

  def processNewEvent(event: NotificationEvent): (Notification, NotificationItem) = {
    val (notifOpt, items) = db.readOnlyMaster { implicit session =>
      val notifOpt = notificationRepo.getLastByUserAndKind(event.userId, event.kind)
      notifOpt.fold(notifOpt, Set.empty[NotificationItem]) { notif =>
        (notifOpt, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    val notif = notifOpt match {
      case Some(existingNotif) if shouldGroupWith(event, items) => existingNotif
      case _ =>
        db.readWrite { implicit session =>
          notificationRepo.save(Notification(
            userId = event.userId,
            kind = event.kind
          ))
        }
    }
    val newItem = db.readWrite { implicit session =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event
      ))
    }
    (notif, newItem)
  }

}
