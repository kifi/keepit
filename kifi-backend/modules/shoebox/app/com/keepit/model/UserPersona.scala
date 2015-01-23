package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.dbmodel.Persona
import org.joda.time.DateTime

case class UserPersona(
    id: Option[Id[UserPersona]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    personaId: Id[Persona],
    state: State[UserPersona] = UserPersonaStates.ACTIVE) extends ModelWithState[UserPersona] {
  def withId(id: Id[UserPersona]): UserPersona = copy(id = Some(id))
  def withUpdateTime(now: DateTime): UserPersona = copy(updatedAt = now)
}

object UserPersonaStates extends States[UserPersona]
