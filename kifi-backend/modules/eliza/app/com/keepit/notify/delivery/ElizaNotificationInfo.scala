package com.keepit.notify.delivery

import com.keepit.eliza.model.{Notification, NotificationItem}
import com.keepit.notify.info.NotificationInfo
import play.api.libs.json.{Json, JsValue}
import com.keepit.common.time._

object ElizaNotificationInfo {

  def mkJson(notif: Notification, items: Set[NotificationItem], info: NotificationInfo): JsValue = Json.obj(
    "id" -> NotificationItem.externalIdFromItems(items),
    "time" -> items.toSeq.sortBy(_.event.time)(dateTimeOrdering.reverse),
    "thread" -> message.threadExtId.id,
    "unread" -> unread,
    "category" -> categoryString,
    "fullCategory" -> category.category,
    "title" -> title,
    "bodyHtml" -> body,
    "linkText" -> linkText,
    "url" -> linkUrl,
    "isSticky" -> sticky,
    "image" -> imageUrl,
    "extra" -> extra
  )

  List(1, 2, 3).sor

}
