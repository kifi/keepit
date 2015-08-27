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

  def updateNotificationStatus(id: ExternalId[Notification], mute: Boolean): Boolean = {
    db.readWrite { implicit session =>
      notificationRepo.getOpt(id).exists { notif =>
        if (notif.disabled == mute) {
          false
        } else {
          notificationRepo.save(notif.copy(disabled = mute))
          true
        }
      }
    }
  }

}
