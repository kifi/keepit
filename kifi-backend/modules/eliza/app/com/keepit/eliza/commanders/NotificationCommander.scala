package com.keepit.eliza.commanders

import com.google.inject.{Provider, ImplementedBy, Inject, Singleton}
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.core.{anyExtensionOps, iterableExtensionOps}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.store.StaticImageUrls
import com.keepit.common.util.{TimedComputation, Ord}
import com.keepit.common.util.Ord.dateTimeOrdering
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.model.{NotificationCategory, User, Keep}
import com.keepit.notify.delivery.{NotificationJsonFormat, WsNotificationDelivery}
import com.keepit.notify.info.{PublicImage, StandardNotificationInfo}
import com.keepit.notify.model.event.{LibraryNewKeep, NotificationEvent}
import com.keepit.notify.model.{NotificationKind, GroupingNotificationKind, NKind, Recipient}
import play.api.libs.json.{JsArray, JsNull, Json}

import scala.concurrent.{Future, ExecutionContext}


@ImplementedBy(classOf[NotificationCommanderImpl])
trait NotificationCommander {
  def getItems(notification: Id[Notification]): Set[NotificationItem]
  def setNotificationDisabledTo(notifId: Id[Notification], disabled: Boolean): Boolean
  def setNotificationUnreadTo(notifId: Id[Notification], unread: Boolean): Boolean
  def processNewEvent(event: NotificationEvent): NotificationWithItems
  def processNewEvents(events: Seq[NotificationEvent]): Seq[NotificationWithItems]
  def completeNotification(kind: NKind, groupIdentifier: String, recipient: Recipient): Boolean
  def getUnreadNotificationsCount(recipient: Recipient): Int
  def sendAnnouncementToUsers(userIds: Set[Id[User]]): Unit
}

@Singleton
class NotificationCommanderImpl @Inject() (
  db: Database,
  notificationRepo: NotificationRepo,
  notificationItemRepo: NotificationItemRepo,
  wsNotificationDelivery: WsNotificationDelivery,
  notificationRouter: WebSocketRouter,
  private implicit val publicIdConfiguration: PublicIdConfiguration,
  implicit val executionContext: ExecutionContext)
    extends NotificationCommander with Logging {

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
  private def shouldGroupWith(events: Set[NotificationEvent], items: Set[NotificationItem]): Boolean = {
    events.forall { event =>
      val existingEvents = items.map(_.event).asInstanceOf[Set[event.N]]
      event.kind.shouldGroupWith(event, existingEvents)
    }
  }

  private def getGroupIdentifier(event: NotificationEvent): Option[String] = {
    Some(event.kind).collect {
      case kind: GroupingNotificationKind[event.N, _] => kind.gid.serialize(kind.getIdentifier(event))
    }
  }

  def processNewEvent(event: NotificationEvent): NotificationWithItems = processNewEvents(Seq(event)).head

  def processNewEvents(events: Seq[NotificationEvent]): Seq[NotificationWithItems] = {
    val notifsWithDuplicates = db.readWrite { implicit s =>
      events.groupBy(e => (e.recipient, e.kind, getGroupIdentifier(e))).flatMap {
        case ((recip, kind, Some(groupIdentifier)), groupableEvents) => groupableEvents.sortBy(_.time).map(event => processGroupedEvents(recip, kind, groupIdentifier, Set(event)))
        case (_, ungroupedEvents) => ungroupedEvents.map(processUngroupedEvent)
      }
    }
    notifsWithDuplicates.toSeq.sortBy(_.notification.lastEvent)(Ord.descending).distinctBy(_.notification.id.get) tap {
      _.foreach { nwi => wsNotificationDelivery.deliver(nwi.notification.recipient, nwi) }
    }
  }
  private def processUngroupedEvent(event: NotificationEvent)(implicit session: RWSession): NotificationWithItems = {
    val notif = notificationRepo.save(Notification(
      recipient = event.recipient,
      kind = event.kind,
      groupIdentifier = None,
      lastEvent = event.time
    ))

    val item = notificationItemRepo.save(NotificationItem(
      notificationId = notif.id.get,
      kind = event.kind,
      event = event,
      eventTime = event.time
    ))

    NotificationWithItems(notif, Set(item))
  }
  private def processGroupedEvents(recipient: Recipient, kind: NKind, identifier: String, events: Set[NotificationEvent])(implicit session: RWSession): NotificationWithItems = {
    val latestEventTime = events.map(_.time).max
    val notif = notificationRepo.getMostRecentByGroupIdentifier(recipient, kind, identifier).filter { existingNotif =>
      val existingItems = notificationItemRepo.getAllForNotification(existingNotif.id.get)

      // God help me for this travesty
      existingItems.map(_.event) match {
        case Seq(oldEvent: LibraryNewKeep) => session.onTransactionSuccess {
          wsNotificationDelivery.delete(recipient, Keep.publicId(oldEvent.keepId).id)
        }
        case _ =>
      }
      shouldGroupWith(events, existingItems.toSet)
    }.getOrElse(notificationRepo.save(Notification(
      recipient = recipient,
      kind = kind,
      groupIdentifier = Some(identifier),
      lastEvent = latestEventTime
    )))

    events.foreach { event =>
      notificationItemRepo.save(NotificationItem(
        notificationId = notif.id.get,
        kind = event.kind,
        event = event,
        eventTime = event.time
      ))
    }

    val allItems = notificationItemRepo.getAllForNotification(notif.id.get).toSet
    NotificationWithItems(
      notification = notificationRepo.save(notif.copy(lastEvent = allItems.map(_.eventTime).max)),
      items = allItems
    )
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

  def sendAnnouncementToUsers(userIds: Set[Id[User]]): Unit = {
    import com.keepit.common.time._
    val notifJson = Json.obj(
      "bodyHtml" -> "The last day to use the Kifi service will be August 22nd, 2016. Click to learn how to export all of your personal data and thank you for being a part of Kifi.",
      "category" -> "global",
      "fullCategory" -> NotificationCategory.User.ANNOUNCEMENT,
      "id" -> "",
      "image" -> StaticImageUrls.KIFI_LOGO,
      "isSticky" -> true,
      "linkText" -> "Learn how to export your keeps",
      "locator" -> JsNull,
      "thread" -> JsNull,
      "time" -> currentDateTime,
      "title" -> "The Kifi team is joining Google",
      "unread" -> true,
      "unreadAuthors" -> 1,
      "unreadMessages" -> 1,
      "url" -> "https://medium.com/p/f1cd2f2e116c"
    )

    userIds.foreach { userId => notificationRouter.sendToUser(userId, Json.arr("notification", notifJson)) }
  }
}
