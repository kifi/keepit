package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
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

  private def getGroupIdentifier(event: NotificationEvent): Option[String] = {
    event.kind.asInstanceOf[NotificationKind[NotificationEvent]].groupIdentifier(event)
  }

  private def saveToExistingNotification(notifId: Id[Notification], event: NotificationEvent, groupIdentifier: Option[String] = None): Notification = {
    db.readWrite { implicit session =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notifId,
        kind = event.kind,
        event = event,
        groupIdentifier = groupIdentifier
      ))
      notificationRepo.get(notifId)
    }
  }

  private def createNewNotification(event: NotificationEvent, groupIdentifier: Option[String] = None): Notification = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.save(Notification(
        userId = event.userId,
        kind = event.kind
      ))
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event,
        groupIdentifier = groupIdentifier
      ))
      notif
    }
  }

  def processNewEvent(event: NotificationEvent): Notification = {
    val groupIdentifier = getGroupIdentifier(event)
    groupIdentifier match {
      case Some(identifier) =>
        db.readOnlyMaster { implicit session =>
          notificationItemRepo.getByGroupIdentifier(identifier)
        } match {
          case Some(item) => saveToExistingNotification(item.notificationId, event, Some(identifier))
          case None => createNewNotification(event)
        }
      case None => {
        db.readOnlyMaster { implicit session =>
          notificationRepo.getLastByUserAndKind(event.userId, event.kind)
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

  }

}
