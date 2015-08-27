package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{NotificationRepo, Notification}
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.User
import play.api.libs.json.Json

class NotificationMessagingCommander @Inject() (
  notificationCommander: NotificationCommander,
  notificationRepo: NotificationRepo,
  db: Database,
  webSocketRouter: WebSocketRouter,
  messagingAnalytics: MessagingAnalytics
) {

  def notificationByExternalId(notifId: ExternalId[Notification]): Option[Notification] = {
    db.readOnlyMaster { implicit session =>
      notificationRepo.getOpt(notifId)
    }
  }

  /**
   * Temporary before replacing completely with new system
   */
  def ifExists(potentialNotifId: String)(doIfExists: Notification => Unit)(doElse: => Unit): Unit = {
    val notifOpt = ExternalId.asOpt[Notification](potentialNotifId).flatMap { id => notificationByExternalId(id) }
    notifOpt.fold(doElse) { notif => doIfExists(notif) }
  }

  def changeNotificationStatus(userId: Id[User], notifId: ExternalId[Notification], disabled: Boolean)(implicit context: HeimdalContext) = {
    val updated = notificationCommander.updateNotificationStatus(notifId, disabled)
    if (updated) {
      webSocketRouter.sendToUser(userId, Json.arr("thread_muted", notifId.id, disabled))
      messagingAnalytics.changedMute(userId, notifId, disabled, context)
    }
  }


}
