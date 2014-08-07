package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.time._
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
  def withUpdateFeedback(feedback: UriRecommendationFeedback): UriRecommendation = this.copy(
    seen = feedback.seen.getOrElse(seen),
    clicked = feedback.clicked.getOrElse(clicked),
    kept = feedback.kept.getOrElse(kept)
  )
}

object UriRecommendationStates extends States[UriRecommendation]

