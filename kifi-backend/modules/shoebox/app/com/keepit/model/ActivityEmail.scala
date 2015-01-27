package com.keepit.model

import com.keepit.common.db.{ Model, State, Id }

import com.keepit.common.time._
import org.joda.time.DateTime

case class ActivityEmail(
    id: Option[Id[ActivityEmail]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ActivityEmail]) extends Model[ActivityEmail] {
  def withId(id: Id[ActivityEmail]): ActivityEmail = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
}
