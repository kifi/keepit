package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.model.NotificationCategory
import com.keepit.notify.NotificationExperimentCheck
import com.keepit.notify.info.{ NotificationInfoProcessing, NotificationInfo }
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
    notifExperimentCheck: NotificationExperimentCheck,
    notificationInfoProcessing: NotificationInfoProcessing,
    implicit val executionContext: ExecutionContext) {

  def deliver(recipient: Recipient, notif: Notification, items: Set[NotificationItem], infoOpt: Option[NotificationInfo]): Future[Unit] = {
    val id = NotificationItem.externalIdFromItems(items)
    val events = items.map(_.event)
    val kind = events.head.kind.asInstanceOf[NotificationKind[NotificationEvent]]
    infoOpt.fold(notificationInfoProcessing.process(kind.info(events), None)) { info =>
      Future.successful(info)
    }.map { info =>
      val notifJson = ElizaNotificationInfo.mkJson(notif, items, info)
      notifExperimentCheck.ifExperiment(recipient) {
        case UserRecipient(user, _) => notificationRouter.sendToUser(user, Json.arr("notification", notifJson))
        case _ =>
      }
    }
  }

}
