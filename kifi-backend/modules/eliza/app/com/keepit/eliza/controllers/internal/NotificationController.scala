package com.keepit.eliza.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ ElizaServiceController, ServiceController }
import com.keepit.common.db.ExternalId
import com.keepit.common.service.ServiceType
import com.keepit.eliza.commanders.{ MessagingAnalytics, NotificationCommander }
import com.keepit.eliza.model.{ MessageThread, ElizaMessage }
import com.keepit.model.NotificationCategory
import com.keepit.notify.model.{ Recipient, GroupingNotificationKind, NKind, UserRecipient }
import com.keepit.notify.model.event.NotificationEvent
import play.api.libs.json.Json
import play.api.mvc.{ Action, Controller }

@Singleton
class NotificationController @Inject() (
    notificationCommander: NotificationCommander,
    messagingAnalytics: MessagingAnalytics) extends ElizaServiceController {

  def postEvent = Action(parse.json) { implicit request =>
    val event = request.body.as[NotificationEvent]
    val notifWithItems = notificationCommander.processNewEvent(event)
    event.recipient match {
      case UserRecipient(id) =>
        messagingAnalytics.sentGlobalNotification(
          Set(id),
          notifWithItems.notification.externalId,
          notifWithItems.relevantItem.externalId,
          NotificationCategory("new_system")
        )
      case _ =>
    }
    Ok(Json.toJson(notifWithItems.notification))
  }

  def completeNotification = Action(parse.json) { implicit request =>
    val body = request.body
    val recipient = (body \ "recipient").as[Recipient]
    val kind = (body \ "kind").as[NKind]
    val groupIdentifier = (body \ "groupIdentifier").as[String]
    val result = notificationCommander.completeNotification(kind, groupIdentifier, recipient)
    Ok(Json.toJson(result))
  }

}
