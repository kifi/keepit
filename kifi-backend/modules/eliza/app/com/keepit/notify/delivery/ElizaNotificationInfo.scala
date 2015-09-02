package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.notify.info.{PublicImage, UserImage, NotificationImage, NotificationInfo}
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

import scala.concurrent.Future

class ElizaNotificationInfo @Inject() (
  shoeboxServiceClient: ShoeboxServiceClient
) {

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
      "image" -> info.imageUrl,
      "extra" -> info.extraJson
    )
  }

  def resolveImage(image: NotificationImage): Future[String] = image match {
    case UserImage(userId) => shoeboxServiceClient.getUserImageUrl(userId, 200)
    case PublicImage(url) => url
  }

}
