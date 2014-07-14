package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, State, ModelWithState, Id }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.model.User
import org.joda.time.DateTime
import com.keepit.common.time._

case class UserTopicMean(mean: Array[Float])

case class UserLDATopic(
    id: Option[Id[UserLDATopic]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    version: ModelVersion[DenseLDA],
    userTopicMean: Option[UserTopicMean],
    state: State[UserLDATopic] = UserLDATopicStates.ACTIVE) extends ModelWithState[UserLDATopic] {
  def withId(id: Id[UserLDATopic]): UserLDATopic = copy(id = Some(id))
  def withUpdateTime(time: DateTime): UserLDATopic = copy(updatedAt = time)
  def withState(state: State[UserLDATopic]): UserLDATopic = copy(state = state)
}

object UserLDATopicStates extends States[UserLDATopic]
