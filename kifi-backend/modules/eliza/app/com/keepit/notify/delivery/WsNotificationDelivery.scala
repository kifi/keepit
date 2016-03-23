package com.keepit.notify.delivery

import com.google.inject.{ Provider, Singleton, Inject }
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems, Notification, NotificationItem }
import com.keepit.model.NotificationCategory
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.info.{ NotificationInfoGenerator, StandardNotificationInfo }
import com.keepit.notify.model._
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@Singleton
class WsNotificationDelivery @Inject() (
    notificationRouter: WebSocketRouter,
    notificationInfoGenerator: NotificationInfoGenerator,
    notificationJsonFormat: Provider[NotificationJsonFormat],
    implicit val executionContext: ExecutionContext) {

  def delete(recipient: Recipient, notifId: String): Unit = {
    recipient match {
      case UserRecipient(user) => notificationRouter.sendToUser(user, Json.arr("remove_notification", notifId))
      case _ =>
    }
  }

  def deliver(recipient: Recipient, notif: NotificationWithItems): Future[Unit] = {
    notificationInfoGenerator.generateInfo(recipient, Seq(notif)).flatMap { infos =>
      notificationJsonFormat.get.extendedJson(infos.head).map { notifJson =>
        recipient match {
          case UserRecipient(user) => notificationRouter.sendToUser(user, Json.arr("notification", notifJson.json))
          case _ =>
        }
      }
    }
  }

}
