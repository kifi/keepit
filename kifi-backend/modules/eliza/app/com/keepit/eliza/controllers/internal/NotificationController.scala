package com.keepit.eliza.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ ElizaServiceController, ServiceController }
import com.keepit.common.service.ServiceType
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.notify.model.NotificationEvent
import play.api.libs.json.Json
import play.api.mvc.{ Action, Controller }

@Singleton
class NotificationController @Inject() (
    val notificationCommander: NotificationCommander) extends ElizaServiceController {

  def postEvent = Action(parse.json) { implicit request =>
    val event = request.body.as[NotificationEvent]
    val notif = notificationCommander.processNewEvent(event)
    Ok(Json.toJson(notif))
  }

}
