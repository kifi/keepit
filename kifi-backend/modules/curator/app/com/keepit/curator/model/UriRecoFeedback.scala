package com.keepit.curator.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.model.{ UriRecommendationFeedback, NormalizedURI, User }
import org.joda.time.DateTime

case class UriRecoFeedback(
    id: Option[Id[UriRecoFeedback]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    uriId: Id[NormalizedURI],
    feedback: UriRecoFeedbackValue,
    state: State[UriRecoFeedback] = UriRecoFeedbackStates.ACTIVE) extends ModelWithState[UriRecoFeedback] {

  def withId(id: Id[UriRecoFeedback]): UriRecoFeedback = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UriRecoFeedback = this.copy(updatedAt = updateTime)
}

object UriRecoFeedbackStates extends States[UriRecoFeedback]

object UriRecoFeedback {
  def fromUserFeedback(userId: Id[User], uriId: Id[NormalizedURI], fb: UriRecommendationFeedback): Option[UriRecoFeedback] = {
    val fbValueOpt = UriRecoFeedbackValue.fromRecoFeedback(fb)
    fbValueOpt.map { value => UriRecoFeedback(userId = userId, uriId = uriId, feedback = value) }
  }
}

case class UriRecoFeedbackValue(value: String)

object UriRecoFeedbackValue {
  val VIEWED = UriRecoFeedbackValue("viewed")
  val CLICKED = UriRecoFeedbackValue("clicked")
  val KEPT = UriRecoFeedbackValue("kept")
  val LIKE = UriRecoFeedbackValue("like")
  val DISLIKE = UriRecoFeedbackValue("dislike")

  def fromRecoFeedback(fb: UriRecommendationFeedback): Option[UriRecoFeedbackValue] = {
    val clickedOpt = fb.clicked match {
      case Some(true) => Some(CLICKED)
      case _ => None
    }

    lazy val keptOpt = fb.kept match {
      case Some(true) => Some(KEPT)
      case _ => None
    }

    lazy val voteOpt = fb.vote match {
      case Some(true) => Some(LIKE)
      case Some(false) => Some(DISLIKE)
      case _ => None
    }

    clickedOpt orElse keptOpt orElse voteOpt
  }
}
