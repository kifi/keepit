package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.User
import com.keepit.notify.model.{ UserRecipient, Recipient }
import com.keepit.notify.model.event.NewMessage
import com.keepit.realtime.{ MobilePushNotifier, MessageThreadPushNotification }
import org.joda.time.DateTime
import play.api.libs.json.Json

class NotificationMessagingCommander @Inject() (
    notificationCommander: NotificationCommander,
    pushNotifier: MobilePushNotifier,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    db: Database,
    webSocketRouter: WebSocketRouter,
    messagingAnalytics: MessagingAnalytics,
    userThreadRepo: UserThreadRepo,
    messageThreadRepo: MessageThreadRepo) {

  private def messageThreadIdForNotif(notif: Notification): ExternalId[MessageThread] = db.readOnlyMaster { implicit session =>
    val userThread = userThreadRepo.getByNotificationId(notif.id.get)
    // messaging analytics expects a message thread id, for now
    userThread.fold(ExternalId[MessageThread](notif.externalId.id)) { userThread =>
      messageThreadRepo.get(userThread.threadId).externalId
    }
  }

  def sendUnreadNotifications(notif: Notification, recipient: Recipient): Unit = {
    val (unreadMessages, unreadNotifications) = db.readOnlyMaster { implicit session =>
      (notificationRepo.getUnreadEnabledNotificationsCountForKind(recipient, NewMessage.name),
        notificationRepo.getUnreadEnabledNotificationsCountExceptKind(recipient, NewMessage.name))
    }
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
        val pushNotif = MessageThreadPushNotification(ExternalId[MessageThread](notif.externalId.id), unreadMessages + unreadNotifications, None, None)
        pushNotifier.notifyUser(user, pushNotif, false)
      case _ =>
    }
  }

  def changeNotificationDisabled(userId: Id[User], notif: Notification, disabled: Boolean)(implicit context: HeimdalContext) = {
    val updated = notificationCommander.setNotificationDisabledTo(notif.id.get, disabled)
    if (updated) {
      webSocketRouter.sendToUser(userId, Json.arr("thread_muted", notif.externalId, disabled))
      messagingAnalytics.changedMute(userId, messageThreadIdForNotif(notif), disabled, context)
    }
  }

  def changeNotificationUnread(userId: Id[User], notif: Notification, item: NotificationItem, unread: Boolean)(implicit context: HeimdalContext): Unit = {
    val updated = notificationCommander.setNotificationUnreadTo(notif.id.get, unread)
    if (updated) {
      val nUrl = notificationCommander.getURI(notif.id.get)
      webSocketRouter.sendToUser(userId, Json.arr(if (unread) "message_unread" else "message_read", nUrl, notif.externalId, item.eventTime, item.externalId))
      if (unread) {
        messagingAnalytics.clearedNotification(userId, ExternalId[Message](item.externalId.id), ExternalId[MessageThread](notif.externalId.id), context)
      }
      sendUnreadNotifications(notif, Recipient(userId))
    }
  }

  def setNotificationsUnreadBefore(notif: Notification, recipient: Recipient, item: NotificationItem): Unit = {
    db.readWrite { implicit session =>
      notificationRepo.setAllReadBefore(recipient, item.eventTime)
    }
    sendUnreadNotifications(notif, recipient)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("all_notifications_visited", item.externalId, item.eventTime))
      case _ =>
    }

  }

}
