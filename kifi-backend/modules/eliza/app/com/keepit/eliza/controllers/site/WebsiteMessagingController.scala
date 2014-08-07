package com.keepit.eliza.controllers.site

import com.keepit.eliza.commanders.{ NotificationCommander, MessagingCommander }
import com.keepit.common.controller.{ WebsiteController, ElizaServiceController, ActionAuthenticator }
import com.keepit.common.time._
import com.keepit.heimdal._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject

class WebsiteMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    notificationCommander: NotificationCommander,
    actionAuthenticator: ActionAuthenticator,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends WebsiteController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = JsonAction.authenticatedAsync { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt)
    }
    noticesFuture.map { notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }
}
