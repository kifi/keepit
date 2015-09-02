package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.notify.info.{ PublicImage, UserImage, NotificationImage, NotificationInfo }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

class ElizaNotificationInfo @Inject() (
    notificationCommander: NotificationCommander,
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    implicit val executionContext: ExecutionContext) {

  import Ordering.Implicits._

  def mkJson(notif: Notification, items: Set[NotificationItem], info: NotificationInfo): JsValue = {
    val unread = !notif.disabled && notificationCommander.isNotificationUnread(notif.id.get)
    val maxByTime = items.maxBy(_.event.time)
    val maxTime = maxByTime.event.time
    Json.obj(
      "id" -> maxByTime.externalId,
      "time" -> maxTime,
      "thread" -> notif.externalId,
      "unread" -> unread,
      "category" -> "triggered",
      "fullCategory" -> "replace me",
      "title" -> info.title,
      "bodyHtml" -> info.body,
      "linkText" -> info.linkText,
      "url" -> info.url,
      "isSticky" -> false,
      "image" -> resolveImage(info.image),
      "extra" -> info.extraJson
    )
  }

  def resolveImage(image: NotificationImage): String = image match {
    case UserImage(user) => elizaS3ExternalIdImageStore.avatarUrlByUser(user)
    case PublicImage(url) => url
  }

}
