package com.keepit.eliza.controllers.site

import com.keepit.eliza.commanders.{ NotificationCommander, MessagingCommander }
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.time._
import com.keepit.heimdal._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject

class WebsiteMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    notificationCommander: NotificationCommander,
    val userActionsHelper: UserActionsHelper,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends UserActions with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = false)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = false)
    }
    noticesFuture.map { notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }
}
