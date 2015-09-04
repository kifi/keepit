package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model._
import com.keepit.model.{ User, NotificationCategory }
import com.keepit.notify.model.event.LegacyNotification
import com.keepit.notify.model.{ Recipient, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json }

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    userThreadRepo: UserThreadRepo,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    notificationDeliveryCommander: NotificationDeliveryCommander) extends Logging {

  def notificationByExternalId(notifId: ExternalId[Notification]): Option[Notification] = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getOpt(notifId)
    }
  }

  def notificationItemByExternalId(notifItemId: ExternalId[NotificationItem]): Option[NotificationItem] = {
    db.readOnlyMaster { implicit session =>
      notificationItemRepo.getOpt(notifItemId)
    }
  }

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

  def backfillLegacyNotificationsFor(userId: Id[User], rawNotifs: Seq[RawNotification]): Seq[(Notification, NotificationItem)] = {
    val recipient = Recipient(userId)
    db.readWrite { implicit session =>
      rawNotifs.map {
        case (json, unread, uri) =>
          val time = (json \ "time").as[DateTime]
          val id = (json \ "id").as[String]
          notificationRepo.getByKindAndGroupIdentifier(LegacyNotification, id).fold {
            val notif = notificationRepo.save(Notification(
              recipient = recipient,
              kind = LegacyNotification,
              lastEvent = time,
              groupIdentifier = Some(id)
            ))
            val item = notificationItemRepo.save(NotificationItem(
              notificationId = notif.id.get,
              kind = LegacyNotification,
              event = LegacyNotification(
                recipient = recipient,
                time = time,
                json = json,
                uriId = uri
              ),
              eventTime = time
            ))
            (notif, item)
          } { notif =>
            (notif, notificationItemRepo.getAllForNotification(notif.id.get).head)
          }
      }
    }
  }

}
