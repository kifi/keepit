package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.model.NotificationCategory
import com.keepit.notify.info.{ NotificationInfo, NotificationInfoGenerator }
import com.keepit.notify.model.{ UserRecipient, NotificationKind, NotificationEvent }
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

class WsNotificationDelivery @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    deliveryCommander: NotificationDeliveryCommander) extends NotificationDelivery {

  def generateInfo(events: Set[NotificationEvent]): Future[NotificationInfo] = {
    shoeboxServiceClient.generateNotificationInfos(events)
  }

  override def deliver(events: Set[NotificationEvent])(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      info <- generateInfo(events)
    } yield {
      events.head.recipient match {
        case UserRecipient(userId) => deliveryCommander.createGlobalNotification(
          userIds = Set(userId),
          title = info.title,
          body = info.body,
          linkText = info.linkText,
          linkUrl = info.path.absolute,
          imageUrl = info.imageUrl,
          sticky = false,
          category = NotificationCategory.User.ANNOUNCEMENT,
          unread = false,
          extra = info.extraJson
        )
      }
      ()
    }
  }

}
