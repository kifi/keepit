package com.keepit.model

import com.keepit.common.db.{Model, Id}
import com.keepit.common.time._
import org.joda.time.DateTime

case class DelightedUser(
  id: Option[Id[DelightedUser]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User]) extends Model[DelightedUser] {
  def withId(id: Id[DelightedUser]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}
