package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import org.joda.time.DateTime
import com.keepit.common.time._

case class SystemValue(
    id: Option[Id[SystemValue]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: Name[SystemValue],
    value: String,
    state: State[SystemValue] = SystemValueStates.ACTIVE) extends ModelWithState[SystemValue] {

  def withId(id: Id[SystemValue]) = this.copy(id = Some(id))
  def withState(newState: State[SystemValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object SystemValueStates extends States[SystemValue]
