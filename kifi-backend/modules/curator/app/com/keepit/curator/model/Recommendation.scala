package com.keepit.curator.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.{ User, NormalizedURI }
import org.joda.time.DateTime
import play.api.libs.json.JsObject

case class Recommendation(
    id: Option[Id[Recommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    uriId: Id[NormalizedURI],
    userId: Id[User],
    finalScore: Float,
    allScores: JsObject,
    seen: Boolean,
    clicked: Boolean,
    kept: Boolean,
    state: State[Recommendation] = RecommendationStates.ACTIVE) extends Model[Recommendation] {

  def withId(id: Id[Recommendation]): Recommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): Recommendation = this.copy(updateAt = updateTime)
}

object RecommendationStates extends States[Recommendation]

