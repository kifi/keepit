package com.keepit.model

import com.keepit.common.db.{ ExternalId, Id, ModelWithExternalId }
import com.keepit.common.time._
import com.keepit.heimdal.DelightedAnswerSource
import org.joda.time.DateTime

case class DelightedAnswer(
    id: Option[Id[DelightedAnswer]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[DelightedAnswer] = ExternalId(),
    delightedExtAnswerId: String, // Assigned by Delighted
    delightedUserId: Id[DelightedUser],
    date: DateTime,
    score: Int,
    comment: Option[String],
    source: DelightedAnswerSource) extends ModelWithExternalId[DelightedAnswer] {
  def withId(id: Id[DelightedAnswer]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withExternalId(id: ExternalId[DelightedAnswer]) = copy(externalId = id)
}
