package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, State, ModelWithState, Id }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.User
import org.joda.time.DateTime
import com.keepit.common.time._

case class UserTopicMean(mean: Array[Float])

case class UserLDAInterests(
    id: Option[Id[UserLDAInterests]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    version: ModelVersion[DenseLDA],
    userTopicMean: Option[UserTopicMean],
    state: State[UserLDAInterests] = UserLDATopicStates.ACTIVE) extends ModelWithState[UserLDAInterests] {
  def withId(id: Id[UserLDAInterests]): UserLDAInterests = copy(id = Some(id))
  def withUpdateTime(time: DateTime): UserLDAInterests = copy(updatedAt = time)
  def withState(state: State[UserLDAInterests]): UserLDAInterests = copy(state = state)
}

object UserLDATopicStates extends States[UserLDAInterests] {
  val NOT_APPLICABLE = "not_applicable"
}
