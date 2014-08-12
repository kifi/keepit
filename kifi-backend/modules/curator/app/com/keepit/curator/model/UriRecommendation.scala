package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.{ User, NormalizedURI }
import org.joda.time.DateTime

case class UriRecommendation(
    id: Option[Id[UriRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    state: State[UriRecommendation] = UriRecommendationStates.ACTIVE,
    vote: Option[Boolean] = None,
    uriId: Id[NormalizedURI],
    userId: Id[User],
    masterScore: Float,
    allScores: UriScores,
    seen: Boolean,
    clicked: Boolean,
    kept: Boolean,
    lastPushedAt: Option[DateTime] = None) extends Model[UriRecommendation] with ModelWithPublicId[UriRecommendation] with ModelWithState[UriRecommendation] {

  def withId(id: Id[UriRecommendation]): UriRecommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UriRecommendation = this.copy(updateAt = updateTime)
  def withLastPushedAt(pushedAt: DateTime): UriRecommendation = this.copy(lastPushedAt = Some(pushedAt))
  def withNoLastPushedAt(): UriRecommendation = this.copy(lastPushedAt = None)
}

object UriRecommendationStates extends States[UriRecommendation]

