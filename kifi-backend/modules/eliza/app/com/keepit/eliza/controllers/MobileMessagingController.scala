package com.keepit.eliza.controllers

import com.keepit.eliza.MessagingController
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}

import play.api.libs.json.Json

import com.google.inject.Inject


class MobileMessagingController @Inject() (messagingController: MessagingController, actionAuthenticator: ActionAuthenticator)
  extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int) = AuthenticatedJsonAction { request =>
    val notices = messagingController.getLatestSendableNotifications(request.userId, howMany.toInt)
    val unread = messagingController.getUnreadThreadCount(request.userId)
    Ok(Json.arr("notifications", notices, unread))
  }
}
