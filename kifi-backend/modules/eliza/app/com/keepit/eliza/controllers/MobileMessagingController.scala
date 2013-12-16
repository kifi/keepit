package com.keepit.eliza.controllers

import com.keepit.eliza.MessagingController
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}

import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject


class MobileMessagingController @Inject() (messagingController: MessagingController, actionAuthenticator: ActionAuthenticator)
  extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int) = AuthenticatedJsonAction { request =>
    Async(messagingController.getLatestSendableNotifications(request.userId, howMany.toInt).map{ notices =>
      val numUnreadUnmuted = messagingController.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices, numUnreadUnmuted))
    })
  }
}
