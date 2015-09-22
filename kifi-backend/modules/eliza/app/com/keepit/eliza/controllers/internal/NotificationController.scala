package com.keepit.eliza.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ ElizaServiceController, ServiceController }
import com.keepit.common.db.ExternalId
import com.keepit.common.service.ServiceType
import com.keepit.eliza.commanders.{ MessagingAnalytics, NotificationCommander }
import com.keepit.eliza.model.{ MessageThread, Message }
import com.keepit.model.NotificationCategory
import com.keepit.notify.NotificationProcessing
import com.keepit.notify.model.UserRecipient
import com.keepit.notify.model.event.NotificationEvent
import play.api.libs.json.Json
import play.api.mvc.{ Action, Controller }

@Singleton
class NotificationController @Inject() (
    val notificationProcessing: NotificationProcessing,
    messagingAnalytics: MessagingAnalytics) extends ElizaServiceController {

  def postEvent = Action(parse.json) { implicit request =>
    val event = request.body.as[NotificationEvent]
    val notif = notificationProcessing.processNewEvent(event)
    event.recipient match {
      case UserRecipient(id, _) =>
        notif foreach { notifWithItems =>
          messagingAnalytics.sentGlobalNotification(
            Set(id),
            ExternalId[Message](notifWithItems.relevantItem.externalId),
            ExternalId[MessageThread](notifWithItems.notification.externalId),
            NotificationCategory("new_system")
          )
        }

    }
    Ok(Json.toJson(notif.map(_.notification)))
  }

}
