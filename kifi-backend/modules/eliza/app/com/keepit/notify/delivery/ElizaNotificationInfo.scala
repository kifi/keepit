package com.keepit.notify.delivery

import com.keepit.eliza.model.{ Notification, NotificationItem }
import com.keepit.notify.info.NotificationInfo
import play.api.libs.json.{ Json, JsValue }
import com.keepit.common.time._

object ElizaNotificationInfo {

  import Ordering.Implicits._

  def mkJson(notif: Notification, items: Set[NotificationItem], info: NotificationInfo): JsValue = {
    val maxTime = items.map(_.event.time).max
    Json.obj(
      "id" -> NotificationItem.externalIdFromItems(items),
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

}
