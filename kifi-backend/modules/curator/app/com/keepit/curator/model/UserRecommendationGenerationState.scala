package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Model, Id }
import com.keepit.model.User
import com.keepit.common.time._

import org.joda.time.DateTime

case class UserRecommendationGenerationState(
    id: Option[Id[UserRecommendationGenerationState]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    seq: SequenceNumber[SeedItem] = SequenceNumber.ZERO,
    userId: Id[User]) extends Model[UserRecommendationGenerationState] {

  def withId(id: Id[UserRecommendationGenerationState]): UserRecommendationGenerationState = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UserRecommendationGenerationState = this.copy(updateAt = updateTime)
}
