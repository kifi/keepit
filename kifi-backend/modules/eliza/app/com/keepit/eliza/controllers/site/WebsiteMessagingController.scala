package com.keepit.eliza.controllers.site

import com.google.inject.Inject
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import play.api.libs.json._
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class WebsiteMessagingController @Inject() (
    notificationCommander: NotificationDeliveryCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(time) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(time), howMany.toInt, includeUriSummary = false)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = false)
    }
    noticesFuture.map { notices =>
      val numUnreadUnmuted = notificationCommander.getTotalUnreadUnmutedCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }
}
