package com.keepit.notify.delivery

import com.google.inject.Inject
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

class WsNotificationDelivery @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    notificationRouter: WebSocketRouter,
    legacyNotificationCheck: LegacyNotificationCheck,
    notificationInfoGenerator: NotificationInfoGenerator,
    elizaNotificationInfo: NotificationJsonFormat,
    implicit val executionContext: ExecutionContext) {

  def deliver(recipient: Recipient, notif: NotificationWithItems): Future[Unit] = {
    notificationInfoGenerator.generateInfo(Seq(notif)).flatMap { infos =>
      elizaNotificationInfo.extendedJson(infos.head).map { notifJson =>
        legacyNotificationCheck.ifUserExperiment(recipient) {
          case UserRecipient(user, _) => notificationRouter.sendToUser(user, Json.arr("notification", notifJson.json))
          case _ =>
        }
      }
    }
  }

}
