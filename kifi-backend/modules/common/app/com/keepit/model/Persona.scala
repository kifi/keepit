package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._

case class Persona(
    id: Option[Id[Persona]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: PersonaName,
    state: State[Persona] = PersonaStates.ACTIVE,
    displayName: String,
    iconPath: String,
    activeIconPath: String) extends ModelWithState[Persona] {
  def withId(id: Id[Persona]): Persona = copy(id = Some(id))

  def withUpdateTime(now: DateTime): Persona = copy(updatedAt = now)
}

sealed abstract class PersonaName(val value: String)

object PersonaName {
  implicit def format[T]: Format[PersonaName] =
    Format(__.read[String].map(PersonaName(_)), new Writes[PersonaName] { def writes(o: PersonaName) = JsString(o.value) })

  case object FOODIE extends PersonaName("foodie")
  case object ARTIST extends PersonaName("artist")
  case object PARENT extends PersonaName("parent")
  case object STUDENT extends PersonaName("student")
  case object DESIGNER extends PersonaName("designer")
  case object PHOTOGRAPHER extends PersonaName("photographer")
  case object ATHLETE extends PersonaName("athlete")
  case object TECHIE extends PersonaName("techie")
  case object GAMER extends PersonaName("gamer")
  case object ADVENTURER extends PersonaName("adventurer")

  def apply(str: String) = {
    str.toLowerCase match {
      case FOODIE.value => FOODIE
      case ARTIST.value => ARTIST
      case PARENT.value => PARENT
      case STUDENT.value => STUDENT
      case DESIGNER.value => DESIGNER
      case PHOTOGRAPHER.value => PHOTOGRAPHER
      case ATHLETE.value => ATHLETE
      case TECHIE.value => TECHIE
      case GAMER.value => GAMER
      case ADVENTURER.value => ADVENTURER
    }
  }

  val allPersonas: Set[PersonaName] = Set(FOODIE, ARTIST, PARENT, STUDENT, DESIGNER, PHOTOGRAPHER, ATHLETE, TECHIE, GAMER, ADVENTURER)
}

object PersonaStates extends States[Persona]
