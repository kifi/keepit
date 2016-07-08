package com.keepit.eliza.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.db.Id
import com.keepit.eliza.commanders.{ MessagingAnalytics, NotificationCommander }
import com.keepit.model.{ User, NotificationCategory }
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.notify.model.{ NKind, Recipient, UserRecipient }
import play.api.libs.json.Json
import play.api.mvc.Action
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class NotificationController @Inject() (
    notificationCommander: NotificationCommander,
    messagingAnalytics: MessagingAnalytics) extends ElizaServiceController {

  def postEvent = Action(parse.json) { implicit request =>
    val event = request.body.as[NotificationEvent]
    notificationCommander.processNewEvent(event)
    NoContent
  }

  def postEvents = Action(parse.json) { implicit request =>
    val events = request.body.as[Seq[NotificationEvent]]
    notificationCommander.processNewEvents(events)
    NoContent
  }

  def completeNotification = Action(parse.json) { implicit request =>
    val body = request.body
    val recipient = (body \ "recipient").as[Recipient]
    val kind = (body \ "kind").as[NKind]
    val groupIdentifier = (body \ "groupIdentifier").as[String]
    val result = notificationCommander.completeNotification(kind, groupIdentifier, recipient)
    Ok(Json.toJson(result))
  }

  def sendAnnouncementToUsers = Action(parse.json) { implicit request =>
    val userIds = (request.body \ "userIds").as[Set[Id[User]]]
    notificationCommander.sendAnnouncementToUsers(userIds)
    NoContent
  }

}
