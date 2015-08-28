package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{Notification, NotificationItem}
import com.keepit.model.NotificationCategory
import com.keepit.notify.info.{ NotificationInfoProcessing, NotificationInfo }
import com.keepit.notify.model._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class WsNotificationDelivery @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    deliveryCommander: NotificationDeliveryCommander,
    notificationRouter: WebSocketRouter,
    notificationInfoProcessing: NotificationInfoProcessing) {

  override def deliver(recipient: Recipient, notif: Notification, items: Set[NotificationItem], info: Option[NotificationInfo])(implicit ec: ExecutionContext): Future[Unit] = {
    val id = NotificationItem.externalIdFromItems(items)
    val events = items.map(_.event)
    val kind = events.head.kind
    val infoFut = info.fold(notificationInfoProcessing.process(kind.info(events), None)) { notifInfo =>
      Future.successful(notifInfo)
    }
  }

}
