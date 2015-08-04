package com.keepit.eliza.model

import com.keepit.common.db.{Model, Id}
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json.JsObject

case class NotificationEvent(
  id: Option[Id[NotificationEvent]],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  notification: Id[Notification],
  kind: NotificationActionKind[_],
  fromUser: Id[User],
  toUser: Id[User],
  time: DateTime,
  data: Option[JsObject]) extends NotificationAction(kind, fromUser, toUser, time, data) with Model[NotificationEvent] {

  override def withId(id: Id[NotificationEvent]): NotificationEvent = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationEvent = copy(updatedAt = time)

}
