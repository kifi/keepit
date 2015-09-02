package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ Notification, NotificationItem, NotificationItemRepo, NotificationRepo }
import com.keepit.model.NotificationCategory
import com.keepit.notify.model.NotificationKind
import play.api.libs.json.{ JsObject, Json }
import com.keepit.common.time._

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    notificationDeliveryCommander: NotificationDeliveryCommander) extends Logging {

  def isNotificationUnread(notifId: Id[Notification]): Boolean = {
    db.readOnlyMaster { implicit session =>
      val notif = notificationRepo.get(notifId)
      val lastItem = notificationItemRepo.get(notif.lastEvent.get)
      val lastChecked = notif.lastChecked.map(id => notificationItemRepo.get(id))
      lastChecked.fold(true) { checked =>
        lastItem.event.time > checked.event.time
      }
    }
  }

  def getItems(notification: Id[Notification]): Set[NotificationItem] = {
    db.readOnlyMaster { implicit session =>
      notificationItemRepo.getAllForNotification(notification)
    }.toSet
  }

  def updateNotificationStatus(notif: Notification, disabled: Boolean): Boolean = {
    if (notif.disabled == disabled) {
      false
    } else {
      db.readWrite { implicit session =>
        notificationRepo.save(notif.copy(disabled = disabled))
      }
      true
    }
  }

}
