package com.keepit.eliza.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.model.{NormalizedURI, User}
import com.keepit.notify.delivery.NotificationDeliverer
import com.keepit.notify.model.event.{LegacyNotification, NotificationEvent}
import com.keepit.notify.model.{GroupingNotificationKind, NKind, Recipient}
import org.joda.time.DateTime
import play.api.libs.json.JsValue

import scala.concurrent.ExecutionContext

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    wsNotificationDelivery: NotificationDeliverer,
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
  def backfillLegacyNotificationsFor(userId: Id[User], rawNotifs: Seq[(Id[UserThread], JsValue, Boolean, Option[Id[NormalizedURI]])]): Seq[NotificationWithItems] = { // kill? no usages
    val recipient = Recipient(userId)
    db.readWrite { implicit session =>
      rawNotifs.map {
        case (userThreadId, json, unread, uri) =>
          val time = (json \ "time").as[DateTime]
          val id = userThreadId.id.toString
          // no longer technically works according to original use, just optimized a bit for current use case
          notificationRepo.getByGroupIdentifier(recipient, LegacyNotification, id).fold {
            val threadId = (json \ "thread").as[String]
            val notif = notificationRepo.save(Notification(
              recipient = recipient,
              kind = LegacyNotification,
              lastEvent = time,
              groupIdentifier = Some(id),
              backfilledFor = Some(userThreadId)
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
            Some(NotificationWithItems(notif, Set(item)))
          } { notif =>
            Some(NotificationWithItems(notif, Set()))
          }
      }.collect { case Some(notif) => notif }
    }
  }

  private def shouldGroupWith(event: NotificationEvent, items: Set[NotificationItem]): Boolean = {
    val res = event.kind.shouldGroupWith(event, items.map(_.event).asInstanceOf[Set[event.N]])
    log.info(s"notif_debug for event $event with items $items, decided to group? $res")
    res
  }

  private def getGroupIdentifier(event: NotificationEvent): Option[String] = {
    event.kind match {
      case kind: GroupingNotificationKind[event.N, _] => Some(kind.gid.serialize(kind.getIdentifier(event)))
      case _ => None
    }
  }

  private def saveToExistingNotification(notifId: Id[Notification], event: NotificationEvent): Notification = {
    db.readWrite { implicit session =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notifId,
        kind = event.kind,
        event = event,
        eventTime = event.time
      ))
      val notif = notificationRepo.get(notifId)
      notificationRepo.save(notif.copy(lastEvent = event.time))
      notificationRepo.get(notifId)
    }
  }

  private def createNewNotification(event: NotificationEvent): Notification = {
    db.readWrite { implicit session =>
      val notif = notificationRepo.save(Notification(
        recipient = event.recipient,
        kind = event.kind,
        groupIdentifier = None,
        lastEvent = event.time
      ))
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event,
        eventTime = event.time
      ))
      notificationRepo.get(notif.id.get)
    }
  }

  def processNewEvent(event: NotificationEvent, tryDeliver: Boolean = true): Option[NotificationWithItems] = {
    val groupIdentifier = getGroupIdentifier(event)
    val recipient = event.recipient
    val notif = groupIdentifier match {
      case Some(identifier) =>
        db.readOnlyMaster { implicit session =>
          notificationRepo.getByGroupIdentifier(event.recipient, event.kind, identifier)
        } match {
          case Some(notifGrouped) => saveToExistingNotification(notifGrouped.id.get, event)
          case None => createNewNotification(event)
        }
      case None =>
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
    val items = db.readOnlyMaster { implicit session =>
      notificationItemRepo.getAllForNotification(notif.id.get)
    }.toSet
    if (tryDeliver) {
      wsNotificationDelivery.deliver(recipient, NotificationWithItems(notif, items))
    }
    Some(NotificationWithItems(notif, items))
  }

  def completeNotification(kind: NKind, groupIdentifier: String, recipient: Recipient): Boolean = {
    db.readWrite { implicit session =>
      notificationRepo.getByGroupIdentifier(recipient, kind, groupIdentifier).fold(false) { notif =>
        notificationRepo.save(notif.copy(lastChecked = Some(notif.lastEvent)))
        true
      }
    }
  }

  def getUnreadNotificationsCount(recipient: Recipient): Int = db.readOnlyMaster { implicit session =>
    notificationRepo.getUnreadNotificationsCount(recipient)
  }

}
