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
    viewed: Option[Boolean],
    clicked: Option[Boolean],
    kept: Option[Boolean],
    like: Option[Boolean],
    state: State[UriRecoFeedback] = UriRecoFeedbackStates.ACTIVE) extends ModelWithState[UriRecoFeedback] {

  def withId(id: Id[UriRecoFeedback]): UriRecoFeedback = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UriRecoFeedback = this.copy(updatedAt = updateTime)

  def onView(): UriRecoFeedback = this.copy(viewed = Some(true))
  def onClick(): UriRecoFeedback = this.copy(clicked = Some(true))
  def onKeep(): UriRecoFeedback = this.copy(kept = Some(true))
  def onLike(): UriRecoFeedback = this.copy(like = Some(true))
  def onDislike(): UriRecoFeedback = this.copy(like = Some(false))
}

object UriRecoFeedbackStates extends States[UriRecoFeedback]
