package com.keepit.eliza.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ ElizaServiceController, ServiceController }
import com.keepit.common.service.ServiceType
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.notify.{ NotificationEventSender, NotificationProcessing }
import com.keepit.notify.model.event.NotificationEvent
import play.api.libs.json.Json
import play.api.mvc.{ Action, Controller }

@Singleton
class NotificationController @Inject() (
    val notificationProcessing: NotificationProcessing) extends ElizaServiceController {

  def postEvent = Action(parse.json) { implicit request =>
    val eventWithInfo = request.body.as[NotificationEventSender.EventWithInfo]
    val notif = notificationProcessing.processNewEvent(eventWithInfo.event, eventWithInfo.info)
    Ok(Json.toJson(notif))
  }

}
