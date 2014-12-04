package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Model, Id }
import com.keepit.model.User
import com.keepit.common.time._

import org.joda.time.DateTime

case class LibraryRecommendationGenerationState(
    id: Option[Id[LibraryRecommendationGenerationState]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    seq: SequenceNumber[CuratorLibraryInfo] = SequenceNumber.ZERO,
    userId: Id[User]) extends Model[LibraryRecommendationGenerationState] {

  def withId(id: Id[LibraryRecommendationGenerationState]): LibraryRecommendationGenerationState = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): LibraryRecommendationGenerationState = this.copy(updateAt = updateTime)
}
