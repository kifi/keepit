package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ ModelWithState, State, States, Id }
import com.keepit.common.time._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.User
import org.joda.time.DateTime

case class UserTopicVar(value: Array[Float])

case class UserLDAStats(
    id: Option[Id[UserLDAStats]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    version: ModelVersion[DenseLDA],
    numOfEvidence: Int,
    firstTopic: Option[LDATopic] = None,
    secondTopic: Option[LDATopic] = None,
    thirdTopic: Option[LDATopic] = None,
    firstTopicScore: Option[Float] = None,
    userTopicMean: Option[UserTopicMean],
    userTopicVar: Option[UserTopicVar],
    state: State[UserLDAStats] = UserLDAStatsStates.ACTIVE) extends ModelWithState[UserLDAStats] {
  def withId(id: Id[UserLDAStats]): UserLDAStats = copy(id = Some(id))
  def withUpdateTime(time: DateTime): UserLDAStats = copy(updatedAt = time)
  def withState(state: State[UserLDAStats]): UserLDAStats = copy(state = state)
}

object UserLDAStatsStates extends States[UserLDAStats] {
  val NOT_APPLICABLE = State[UserLDAStats]("not_applicable")
}
