package com.keepit.eliza.controllers.mobile

import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}

import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject


class MobileMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  actionAuthenticator: ActionAuthenticator
  ) extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int) = AuthenticatedJsonAction { request =>
    Async(messagingCommander.getLatestSendableNotifications(request.userId, howMany.toInt).map{ notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices, numUnreadUnmuted))
    })
  }
}
