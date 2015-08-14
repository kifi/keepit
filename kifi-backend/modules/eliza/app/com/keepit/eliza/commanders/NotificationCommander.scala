package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ Notification, NotificationItem, NotificationItemRepo, NotificationRepo }
import com.keepit.model.NotificationCategory
import com.keepit.notify.model.{ DepressedRobotGrumble, NotificationKind, NotificationEvent }
import play.api.libs.json.{ JsObject, Json }

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    notificationDeliveryCommander: NotificationDeliveryCommander) extends Logging {

  private def shouldGroupWith(event: NotificationEvent, items: Set[NotificationItem]): Boolean = {
    val kind = event.kind.asInstanceOf[NotificationKind[NotificationEvent]]
    val res = kind.shouldGroupWith(event, items.map(_.event))
    log.info(s"notif_debug for event $event with items $items, decided to group? $res")
    res
  }

  def processNewEvent(event: NotificationEvent): (Notification, Set[NotificationItem]) = {
    val (notifOpt, items) = db.readOnlyMaster { implicit session =>
      val notifOpt = notificationRepo.getLastByUserAndKind(event.userId, event.kind)

      notifOpt.fold(notifOpt, Set.empty[NotificationItem]) { notif =>
        (notifOpt, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    log.info(s"notif_debug for event $event found $notifOpt with items $items")
    val (grouped, notif) = notifOpt match {
      case Some(existingNotif) if shouldGroupWith(event, items) => (true, existingNotif)
      case _ =>
        (false, db.readWrite { implicit session =>
          notificationRepo.save(Notification(
            userId = event.userId,
            kind = event.kind
          ))
        })
    }
    val newItem = db.readWrite { implicit session =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event
      ))
    }
    val allItems = if (grouped) items + newItem else Set(newItem)
    (notif, allItems)
  }

}
