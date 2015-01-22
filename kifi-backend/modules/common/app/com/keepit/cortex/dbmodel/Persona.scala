package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import org.joda.time.DateTime

case class Persona(
    id: Option[Id[Persona]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: String,
    state: State[Persona] = PersonaStates.ACTIVE) extends ModelWithState[Persona] {
  def withId(id: Id[Persona]): Persona = copy(id = Some(id))

  def withUpdateTime(now: DateTime): Persona = copy(updatedAt = now)
}

object PersonaStates extends States[Persona]
