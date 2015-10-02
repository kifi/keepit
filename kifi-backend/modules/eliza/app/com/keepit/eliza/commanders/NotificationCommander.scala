package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model._
import com.keepit.model.{ NormalizedURI, User, NotificationCategory }
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.delivery.WsNotificationDelivery
import com.keepit.notify.info.{ NotificationKindInfoRequests, LegacyNotificationInfo, NotificationInfo }
import com.keepit.notify.model.event.{NotificationEvent, NewMessage, LegacyNotification}
import com.keepit.notify.model.{NKind, GroupingNotificationKind, Recipient, NotificationKind}
import org.joda.time.DateTime
import play.api.libs.json.{ JsValue, JsObject, Json }
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    userThreadRepo: UserThreadRepo,
    messageRepo: MessageRepo,
    messageThreadRepo: MessageThreadRepo,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    wsNotificationDelivery: WsNotificationDelivery,
    legacyNotificationCheck: LegacyNotificationCheck,
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

  def backfillLegacyNotificationsFor(userId: Id[User], rawNotifs: Seq[(Id[UserThread], JsValue, Boolean, Option[Id[NormalizedURI]])]): Seq[NotificationWithItems] = {
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

  def backfillMessageThreadForUser(userId: Id[User], messageThreadId: Id[MessageThread])(implicit session: RWSession): NotificationWithItems = {
    val recipient = Recipient(userId)
    val userThread = userThreadRepo.getUserThread(userId, messageThreadId)
    val lastChecked = Seq(userThread.lastActive, userThread.lastSeen, userThread.notificationLastSeen).collect {
      case Some(time) => time
    }.maxOpt
    val messages = messageRepo.get(messageThreadId, 0)
    val lastEvent = messages.map(_.createdAt).max
    val groupIdentifier = messageThreadId.id.toString
    notificationRepo.getByGroupIdentifier(recipient, NewMessage, groupIdentifier).fold({
      val notif = notificationRepo.save(Notification(
        recipient = recipient,
        kind = NewMessage,
        lastEvent = lastEvent,
        groupIdentifier = Some(messageThreadId.id.toString)
      ))
      val notifId = notif.id.get
      userThreadRepo.save(userThread.copy(
        notificationId = Some(notifId)
      ))
      val items = messages.map { message =>
        (message, message.from.asRecipient)
      }.collect {
        case (message, Some(from)) => (message, from)
      }.map {
        case (message, from) =>
          notificationItemRepo.save(NotificationItem(
            notificationId = notif.id.get,
            kind = NewMessage,
            event = NewMessage(
              recipient = recipient,
              time = message.createdAt,
              from = from,
              messageId = message.id.get.id,
              messageThreadId = messageThreadId.id
            ),
            eventTime = message.createdAt
          ))
      }
      NotificationWithItems(notif, items.toSet)
    }) { notif =>
      NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
    }
  }

  def updateMessageThreadForUser(userId: Id[User], messageThreadId: Id[MessageThread]): NotificationWithItems = {
    db.readWrite { implicit session =>
      val notifWithItems = backfillMessageThreadForUser(userId, messageThreadId)
      val mostRecent = notifWithItems.relevantItem
      val newItems = messageRepo.getAfter(messageThreadId, mostRecent.eventTime).map { message =>
        (message, message.from.asRecipient)
      }.collect {
        case (message, Some(from)) => (message, from)
      }.map {
        case (message, from) =>
          notificationItemRepo.save(NotificationItem(
            notificationId = notifWithItems.notification.id.get,
            kind = NewMessage,
            event = NewMessage(
              recipient = Recipient(userId),
              time = message.createdAt,
              from = from,
              messageId = message.id.get.id,
              messageThreadId = messageThreadId.id
            ),
            eventTime = message.createdAt
          ))
      }.toSet
      notifWithItems.copy(items = notifWithItems.items | newItems)
    }
  }

  def getNotifForMessageThread(userId: Id[User], messageThreadId: Id[MessageThread]): Option[Notification] = {
    db.readOnlyMaster { implicit session =>
      userThreadRepo.getUserThread(userId, messageThreadId).notificationId.map { id =>
        notificationRepo.get(id)
      }
    }
  }

  /**
   * Gets the message thread that is potentially associated with a notification. This should only return a message thread
   * if the notification kind is of [[com.keepit.notify.model.event.NewMessage]], as that is the only kind associated with
   * message threads.
   */
  def getMessageThread(notifId: Id[Notification])(implicit session: RSession): Option[MessageThread] = {
    userThreadRepo.getByNotificationId(notifId).map { userThread =>
      messageThreadRepo.get(userThread.threadId)
    }
  }

  /**
   * Gets the URI associated with a notification. If the notification represents a [[LegacyNotification]], then getting
   * the URI just means getting the uri off of the notification's event object. Otherwise, the notification may be connected
   * with a message thread that does have a normalized URI associated with it.
   */
  def getURI(notifId: Id[Notification]): Option[Id[NormalizedURI]] = {
    db.readOnlyMaster { implicit session =>
      val items = notificationItemRepo.getAllForNotification(notifId)
      items.head.event match { // these two cases are mutually exclusive. legacy notifications do not have message threads
        case LegacyNotification(_, _, _, uriId) => uriId
        case _ => getMessageThread(notifId).flatMap(_.uriId)
      }
    }
  }

  def getParticipants(notif: Notification): Set[Recipient] = {
    val messageThreadOpt = db.readOnlyReplica { implicit session =>
      getMessageThread(notif.id.get)
    }
    messageThreadOpt.fold(Set(notif.recipient)) { messageThread =>
      messageThread.participants.fold(Set[Recipient]()) { participants =>
        participants.allUsers.map { user =>
          Recipient(user)
        } ++ participants.allNonUsers.collect {
          case NonUserEmailParticipant(address) => Recipient(address)
        }
      }
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
    if (legacyNotificationCheck.checkUserExperiment(recipient).experimentEnabled) {
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
        legacyNotificationCheck.ifUserExperiment(notif.recipient) { recipient =>
          wsNotificationDelivery.deliver(recipient, NotificationWithItems(notif, items))
        }
      }
      Some(NotificationWithItems(notif, items))
    } else {
      None
    }
  }

  def completeNotification(kind: NKind, groupIdentifier: String, recipient: Recipient): Boolean = {
    db.readWrite { implicit session =>
      notificationRepo.getByGroupIdentifier(recipient, kind, groupIdentifier).fold(false) { notif =>
        notificationRepo.save(notif.copy(lastChecked = Some(notif.lastEvent)))
        true
      }
    }
  }

}
