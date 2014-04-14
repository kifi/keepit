package com.keepit.eliza.controllers.site

import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.heimdal._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject


class SiteMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory
  ) extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = JsonAction.authenticatedAsync { request =>
    val noticesFuture = before match {
      case Some(before) =>
        messagingCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt)
      case None =>
        messagingCommander.getLatestSendableNotifications(request.userId, howMany.toInt)
    }
    noticesFuture.map {notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.jsons, numUnreadUnmuted))
    }
  }
}
