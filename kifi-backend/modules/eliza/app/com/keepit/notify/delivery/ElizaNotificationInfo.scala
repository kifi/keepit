package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.notify.info.{ PublicImage, UserImage, NotificationImage, NotificationInfo }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.store.ElizaS3ExternalIdImageStore
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.{ExecutionContext, Future}

class ElizaNotificationInfo @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    elizaS3ExternalIdImageStore: ElizaS3ExternalIdImageStore,
    implicit val executionContext: ExecutionContext) {

  import Ordering.Implicits._

  def mkJson(notif: Notification, items: Set[NotificationItem], info: NotificationInfo): JsValue = {
    val maxByTime = items.maxBy(_.event.time)
    val maxTime = maxByTime.event.time
    Json.obj(
      "id" -> maxByTime.externalId,
      "time" -> maxTime,
      "thread" -> notif.externalId,
      "unread" -> Json.toJson(!notif.disabled && notif.lastEvent > notif.lastChecked),
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
