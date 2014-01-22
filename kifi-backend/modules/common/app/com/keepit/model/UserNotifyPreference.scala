package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._

case class UserNotifyPreference(
  id: Option[Id[UserNotifyPreference]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  name: String,
  canSend: Boolean,
  state: State[UserNotifyPreference] = UserNotifyPreferenceStates.ACTIVE
  ) extends Model[UserNotifyPreference] {
  def withId(id: Id[UserNotifyPreference]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserNotifyPreference]) = copy(state = state)
}

object UserNotifyPreferenceStates extends States[UserNotifyPreference]


