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
import com.keepit.realtime.MessageThreadPushNotification
import org.joda.time.DateTime
import play.api.libs.json.Json

class NotificationMessagingCommander @Inject() (
    notificationCommander: NotificationCommander,
    notificationDeliveryCommander: NotificationDeliveryCommander,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    db: Database,
    webSocketRouter: WebSocketRouter,
    messagingAnalytics: MessagingAnalytics,
    userThreadRepo: UserThreadRepo,
    messageThreadRepo: MessageThreadRepo) {

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

  private def messageThreadIdForNotif(notif: Notification): ExternalId[MessageThread] = db.readOnlyMaster { implicit session =>
    val userThread = userThreadRepo.getByNotificationId(notif.id.get)
    // messaging analytics expects a message thread id, for now
    userThread.fold(notif.externalId.asInstanceOf[ExternalId[MessageThread]]) { userThread =>
      messageThreadRepo.get(userThread.threadId).externalId
    }
  }

  /**
   * Temporary before replacing completely with new system
   */
  def ifExists(potentialNotifId: String)(doIfExists: Notification => Unit)(doElse: => Unit): Unit = {
    val notifOpt = ExternalId.asOpt[Notification](potentialNotifId).flatMap { id => notificationByExternalId(id) }
    notifOpt.fold(doElse) { notif => doIfExists(notif) }
  }

  def ifItemExists(potentialItemId: String)(doIfExists: (Notification, NotificationItem) => Unit)(doElse: => Unit): Unit = {
    val notifItemOpt = ExternalId.asOpt[NotificationItem](potentialItemId).flatMap { id =>
      notificationItemByExternalId(id)
    }.map { item =>
      (db.readOnlyMaster { implicit session =>
        notificationRepo.get(item.notificationId)
      }, item)
    }
    notifItemOpt.fold(doElse) {
      case (notif, item) => doIfExists(notif, item)
    }
  }

  def changeNotificationStatus(userId: Id[User], notif: Notification, disabled: Boolean)(implicit context: HeimdalContext) = {
    val updated = notificationCommander.updateNotificationStatus(notif.id.get, disabled)
    if (updated) {
      webSocketRouter.sendToUser(userId, Json.arr("thread_muted", notif.externalId, disabled))
      messagingAnalytics.changedMute(userId, messageThreadIdForNotif(notif), disabled, context)
    }
  }

  def setNotificationRead(notif: Notification)(implicit context: HeimdalContext): Unit = {
    notificationCommander.setNotificationRead(notif.id.get)
  }

  def setNotificationUnread(notif: Notification): Unit = {
    notificationCommander.setNotificationUnread(notif.id.get)
  }

  def getUnreadEnabledNotificationsCount(recipient: Recipient): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadEnabledNotificationsCount(recipient)
    }
  }

  def getUnreadEnabledNotificationsCountForKind(recipient: Recipient, kind: String): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadEnabledNotificationsCountForKind(recipient, kind)
    }
  }

  def getUnreadEnabledNotificationsCountExceptKind(recipient: Recipient, kind: String): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadEnabledNotificationsCountExceptKind(recipient, kind)
    }
  }

  def sendUnreadNotifications(recipient: Recipient) = {
    val unreadMessages = getUnreadEnabledNotificationsCountForKind(recipient, NewMessage.name)
    val unreadNotifications = getUnreadEnabledNotificationsCountExceptKind(recipient, NewMessage.name)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
      case _ =>
    }
  }

  def setNotificationsUnreadBefore(notif: Notification, recipient: Recipient, item: NotificationItem): Unit = {
    db.readWrite { implicit session =>
      notificationRepo.setAllReadBefore(recipient, item.eventTime)
    }
    val unreadMessages = getUnreadEnabledNotificationsCountForKind(recipient, NewMessage.name)
    val unreadNotifications = getUnreadEnabledNotificationsCountExceptKind(recipient, NewMessage.name)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
        val pushNotif = MessageThreadPushNotification(notif.externalId.asInstanceOf[ExternalId[MessageThread]], unreadMessages + unreadNotifications, None, None)
        notificationDeliveryCommander.sendPushNotification(user, pushNotif)
        webSocketRouter.sendToUser(user, Json.arr("all_notifications_visited", item.externalId, item.eventTime))
      case _ =>
    }

  }

}
