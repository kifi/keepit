package com.keepit.curator.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.model.{ NormalizedURI, User }
import org.joda.time.DateTime

case class UriRecoFeedback(
    id: Option[Id[UriRecoFeedback]],
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

case class UriRecoFeedbackValue(value: String)

object UriRecoFeedbackValue {
  val VIEWED = UriRecoFeedbackValue("viewed")
  val CLICKED = UriRecoFeedbackValue("clicked")
  val KEPT = UriRecoFeedbackValue("kept")
  val LIKE = UriRecoFeedbackValue("like")
  val DISLIKE = UriRecoFeedbackValue("dislike")
}
