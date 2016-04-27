package com.keepit.notify.delivery

import com.google.inject.{ ImplementedBy, Provider, Singleton, Inject }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.NotificationWithItems
import com.keepit.notify.info.NotificationInfoGenerator
import com.keepit.notify.model._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[WsNotificationDeliveryImpl])
trait WsNotificationDelivery {
  def delete(recipient: Recipient, notifId: String): Unit
  def deliver(recipient: Recipient, notif: NotificationWithItems): Future[Unit]
}

@Singleton
class WsNotificationDeliveryImpl @Inject() (
    notificationRouter: WebSocketRouter,
    notificationInfoGenerator: NotificationInfoGenerator,
    notificationJsonFormat: Provider[NotificationJsonFormat],
    implicit val executionContext: ExecutionContext) extends WsNotificationDelivery {

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
