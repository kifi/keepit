package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.model.NotificationCategory
import com.keepit.notify.{ LegacyNotificationCheck }
import com.keepit.notify.info.{ NotificationInfoGenerator, StandardNotificationInfo }
import com.keepit.notify.model._
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class WsNotificationDelivery @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    deliveryCommander: NotificationDeliveryCommander,
    notificationRouter: WebSocketRouter,
    legacyNotificationCheck: LegacyNotificationCheck,
    notificationInfoGenerator: NotificationInfoGenerator,
    elizaNotificationInfo: NotificationJsonFormat,
    implicit val executionContext: ExecutionContext) {

  def deliver(recipient: Recipient, notif: Notification, items: Set[NotificationItem]): Future[Unit] = {
    val events = items.map(_.event)
    notificationInfoGenerator.generateInfo(Map(notif -> items)).flatMap { infos =>
      val (items, info) = infos(notif)
        elizaNotificationInfo.basicJson(notif, items, info).map { notifJson =>
        legacyNotificationCheck.ifUserExperiment(recipient) {
          case UserRecipient(user, _) => notificationRouter.sendToUser(user, Json.arr("notification", notifJson))
          case _ =>
        }
      }
    }
  }

}
