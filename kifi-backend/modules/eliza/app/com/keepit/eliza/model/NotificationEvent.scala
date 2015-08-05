package com.keepit.eliza.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json.JsObject

case class NotificationEvent(
    id: Option[Id[NotificationEvent]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notification: Id[UserThreadNotification],
    kind: NotificationActionKind[_],
    action: NotificationAction) extends Model[NotificationEvent] {

  override def withId(id: Id[NotificationEvent]): NotificationEvent = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationEvent = copy(updatedAt = now)

}
