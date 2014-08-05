package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.UriRecommendationFeedback._
import com.keepit.model.{ UriRecommendationFeedback, User, NormalizedURI }
import org.joda.time.DateTime

case class UriRecommendation(
    id: Option[Id[UriRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    state: State[UriRecommendation] = UriRecommendationStates.ACTIVE,
    uriId: Id[NormalizedURI],
    userId: Id[User],
    masterScore: Float,
    allScores: UriScores,
    seen: Boolean,
    clicked: Boolean,
    kept: Boolean) extends Model[UriRecommendation] with ModelWithPublicId[UriRecommendation] with ModelWithState[UriRecommendation] {

  def withId(id: Id[UriRecommendation]): UriRecommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UriRecommendation = this.copy(updateAt = updateTime)
  def withUpdateFeedbacks(feedbacks: Map[UriRecommendationFeedback.Value, Boolean]): UriRecommendation = this.copy(
    seen = feedbacks.get(UriRecommendationFeedback.seen).get,
    clicked = feedbacks.get(UriRecommendationFeedback.clicked).get,
    kept = feedbacks.get(UriRecommendationFeedback.kept).get
  )
}

object UriRecommendationStates extends States[UriRecommendation]

