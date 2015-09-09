package com.keepit.eliza.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.eliza.model._
import com.keepit.model.{ NormalizedURI, User, NotificationCategory }
import com.keepit.notify.info.{ NotificationKindInfoRequests, LegacyNotificationInfo, NotificationInfo }
import com.keepit.notify.model.event.{ NewMessage, LegacyNotification }
import com.keepit.notify.model.{ Recipient, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json }
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.Future

@Singleton
class NotificationCommander @Inject() (
    db: Database,
    userThreadRepo: UserThreadRepo,
    messageRepo: MessageRepo,
    messageThreadRepo: MessageThreadRepo,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo) extends Logging {

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

  def backfillLegacyNotificationsFor(userId: Id[User], rawNotifs: Seq[RawNotification]): Seq[NotificationWithItems] = {
    val recipient = Recipient(userId)
    db.readWrite { implicit session =>
      rawNotifs.map {
        case (json, unread, uri) =>
          val time = (json \ "time").as[DateTime]
          val id = (json \ "id").as[String]

          notificationRepo.getByGroupIdentifier(recipient, LegacyNotification, id).fold {
            val threadId = (json \ "threadId").as[String]
            val messageThread = messageThreadRepo.get(ExternalId[MessageThread](threadId))
            if (messageThread.replyable) {
              backfillMessageThreadForUser(userId, messageThread)
            } else {
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
              NotificationWithItems(notif, Set(item))
            }
          } { notif =>
            NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
          }
      }
    }
  }

  def backfillMessageThreadForUser(userId: Id[User], messageThread: MessageThread)(implicit session: RWSession): NotificationWithItems = {
    val recipient = Recipient(userId)
    val userThread = userThreadRepo.getUserThread(userId, messageThread.id.get)
    val lastChecked = Seq(userThread.lastActive, userThread.lastSeen, userThread.notificationLastSeen).collect {
      case Some(time) => time
    }.maxOpt
    val messages = messageRepo.get(messageThread.id.get, 0)
    val lastEvent = messages.map(_.createdAt).max
    val groupIdentifier = messageThread.id.get.toString
    notificationRepo.getByKindAndGroupIdentifier(NewMessage, groupIdentifier).fold({
      val notif = notificationRepo.save(Notification(
        recipient = recipient,
        kind = NewMessage,
        lastEvent = lastEvent,
        groupIdentifier = Some(messageThread.id.get.toString)
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
              messageThreadId = messageThread.id.get.id
            ),
            eventTime = message.createdAt
          ))
      }
      NotificationWithItems(notif, items.toSet)
    }) { notif =>
      NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
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

}
