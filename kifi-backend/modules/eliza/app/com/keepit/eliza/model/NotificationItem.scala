package com.keepit.eliza.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NotificationKind, NotificationEvent }
import org.joda.time.DateTime
import play.api.libs.json.JsObject

case class NotificationItem(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notification: Id[Notification],
    kind: NotificationKind[_],
    action: NotificationEvent) extends Model[NotificationItem] {

  override def withId(id: Id[NotificationItem]): NotificationItem = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationItem = copy(updatedAt = now)

}
