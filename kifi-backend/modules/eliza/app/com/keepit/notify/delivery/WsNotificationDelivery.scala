package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationDeliveryCommander
import com.keepit.eliza.model.NotificationItem
import com.keepit.model.NotificationCategory
import com.keepit.notify.info.{ NotificationInfo, NotificationInfoGenerator }
import com.keepit.notify.model._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class WsNotificationDelivery @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    deliveryCommander: NotificationDeliveryCommander) extends NotificationDelivery {

  def generateInfo(id: NotificationId, items: Set[NotificationItem]): Future[Map[NotificationId, NotificationInfo]] = {
    shoeboxServiceClient.generateNotificationInfos(Map(id -> items.map(_.event)))
  }

  override def deliver(recipient: Recipient, items: Set[NotificationItem])(implicit ec: ExecutionContext): Future[Unit] = {
    val id = NotificationItem.externalIdFromItems(items)
    for {
      infoMap <- generateInfo(id, items)
      info <- Future.fromTry(Try { infoMap.get(id).get })
    } yield {
      recipient match {
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
