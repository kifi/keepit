package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.User
import com.keepit.notify.model.Recipient
import play.api.libs.json.Json

class NotificationMessagingCommander @Inject() (
    notificationCommander: NotificationCommander,
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

  def getUnreadNotificationsCount(recipient: Recipient): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadNotificationsCount(recipient)
    }
  }

  def getUnreadNotificationsCountForKind(recipient: Recipient, kind: String): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadNotificationsCountForKind(recipient, kind)
    }
  }

  def getUnreadNotificationsCountExceptKind(recipient: Recipient, kind: String): Int = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getUnreadNotificationsCountExceptKind(recipient)
    }
  }

}
