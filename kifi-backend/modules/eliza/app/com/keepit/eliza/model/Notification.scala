package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.NotificationKind
import org.joda.time.DateTime

case class Notification(
    id: Option[Id[Notification]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    lastChecked: DateTime = START_OF_TIME,
    kind: NotificationKind[_]) extends Model[Notification] {

  override def withId(id: Id[Notification]): Notification = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Notification = copy(updatedAt = updatedAt)

}

