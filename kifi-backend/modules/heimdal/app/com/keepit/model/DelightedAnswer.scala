package com.keepit.model

import com.keepit.common.db.{Model, Id}
import com.keepit.common.time._
import org.joda.time.DateTime

case class DelightedAnswer(
  id: Option[Id[DelightedAnswer]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  delightedUserId: Id[DelightedUser],
  date: DateTime,
  score: Int,
  comment: Option[String]) extends Model[DelightedAnswer] {
  def withId(id: Id[DelightedAnswer]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}
