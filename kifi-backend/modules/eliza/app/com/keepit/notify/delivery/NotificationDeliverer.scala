package com.keepit.notify.delivery

import com.google.inject.{ Provider, Singleton, Inject }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems, Notification, NotificationItem }
import com.keepit.model.NotificationCategory
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.info.{ NotificationInfoGenerator, StandardNotificationInfo }
import com.keepit.notify.model._
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.slack.SlackClient
import com.keepit.slack.models.{ SlackMessageRequest, SlackNotificationVector }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@Singleton
class NotificationDeliverer @Inject() (
    notificationRouter: WebSocketRouter,
    notificationInfoGenerator: NotificationInfoGenerator,
    notificationJsonFormat: Provider[NotificationJsonFormat],
    shoebox: ShoeboxServiceClient,
    slack: SlackClient,
    implicit val executionContext: ExecutionContext) {

  def deliver(recipient: Recipient, notif: NotificationWithItems): Future[Unit] = {
    notificationInfoGenerator.generateInfo(recipient, Seq(notif)).flatMap { infos =>
      notificationJsonFormat.get.extendedJson(infos.head).map { notifJson =>
        recipient match {
          case UserRecipient(user) =>
            val extPushes = notificationRouter.sendToUser(user, Json.arr("notification", notifJson.json))
            val slackPushes = for {
              vectors <- shoebox.getSlackNotificationVectorsForUser(user)
              pushes <- FutureHelpers.accumulateRobustly(vectors) {
                case SlackNotificationVector(slackUserId, slackToken) =>
                  slack.postToChannel(slackToken, slackUserId.asChannel, SlackMessageRequest.fromKifi(Json.stringify(notifJson.json)))
              }
            } yield ()
          case _ =>
        }
      }
    }
  }

}
