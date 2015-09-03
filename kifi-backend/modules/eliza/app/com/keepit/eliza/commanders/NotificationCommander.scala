package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ Notification, NotificationItem, NotificationItemRepo, NotificationRepo }
import com.keepit.model.NotificationCategory
import com.keepit.notify.model.NotificationKind
import play.api.libs.json.{ JsObject, Json }

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    notificationDeliveryCommander: NotificationDeliveryCommander) extends Logging {

  def getItems(notification: Id[Notification]): Set[NotificationItem] = {
    db.readOnlyMaster { implicit session =>
      notificationItemRepo.getAllForNotification(notification)
    }.toSet
  }

  def updateNotificationStatus(notifId: Id[Notification], disabled: Boolean): Boolean = {
    val notif = db.readOnlyMaster { implicit session =>
      notificationRepo.get(notifId)
    }
    if (notif.disabled == disabled) {
      false
    } else {
      db.readWrite { implicit session =>
        notificationRepo.save(notif.copy(disabled = disabled))
      }
      true
    }
  }

  def setNotificationRead(notifId: Id[Notification]): Notification = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.get(notifId)
      notificationRepo.save(notif.copy(lastChecked = Some(notif.lastEvent)))
    }
  }

  def setNotificationUnread(notifId: Id[Notification]): Notification = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.get(notifId)
      notificationRepo.save(notif.copy(lastChecked = None))
    }
  }

}
