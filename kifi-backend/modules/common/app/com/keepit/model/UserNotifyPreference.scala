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
    state: State[UserNotifyPreference] = UserNotifyPreferenceStates.ACTIVE) extends ModelWithState[UserNotifyPreference] {
  def withId(id: Id[UserNotifyPreference]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserNotifyPreference]) = copy(state = state)
}

object UserNotifyPreferenceStates extends States[UserNotifyPreference]

abstract class NotifyPreference(val name: String)
object NotifyPreference {
  case object RECOS_REMINDER extends NotifyPreference("recos_reminder")

  def apply(name: String): NotifyPreference = {
    name match {
      case RECOS_REMINDER.name => NotifyPreference.RECOS_REMINDER
    }
  }
}
