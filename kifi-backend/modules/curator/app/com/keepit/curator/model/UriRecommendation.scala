package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.model.{ User, NormalizedURI }
import org.joda.time.DateTime

case class UriRecommendation(
    id: Option[Id[UriRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[UriRecommendation] = UriRecommendationStates.ACTIVE,
    vote: Option[Boolean] = None,
    uriId: Id[NormalizedURI],
    userId: Id[User],
    masterScore: Float,
    allScores: UriScores,
    viewed: Int = 0,
    clicked: Int = 0,
    kept: Boolean = false,
    trashed: Boolean = false,
    lastPushedAt: Option[DateTime] = None,
    attribution: SeedAttribution,
    topic1: Option[LDATopic],
    topic2: Option[LDATopic]) extends Model[UriRecommendation] with ModelWithPublicId[UriRecommendation] with ModelWithState[UriRecommendation] {

  def withId(id: Id[UriRecommendation]): UriRecommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UriRecommendation = this.copy(updatedAt = updateTime)
  def withLastPushedAt(pushedAt: DateTime): UriRecommendation = this.copy(lastPushedAt = Some(pushedAt))
  def withNoLastPushedAt(): UriRecommendation = this.copy(lastPushedAt = None)
}

object UriRecommendationStates extends States[UriRecommendation]

