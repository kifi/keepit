package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Persona(
    id: Option[Id[Persona]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: PersonaName,
    state: State[Persona] = PersonaStates.ACTIVE,
    displayName: String,
    displayNamePlural: String,
    iconPath: String,
    activeIconPath: String) extends ModelWithState[Persona] {
  def withId(id: Id[Persona]): Persona = copy(id = Some(id))
  def withUpdateTime(now: DateTime): Persona = copy(updatedAt = now)
}

object Persona {
  implicit val personaIdFormat = Id.format[Persona]
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Persona]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'name).format[PersonaName] and
    (__ \ 'state).format(State.format[Persona]) and
    (__ \ 'displayName).format[String] and
    (__ \ 'displayNamePlural).format[String] and
    (__ \ 'iconPath).format[String] and
    (__ \ 'activeIconPath).format[String]
  )(Persona.apply, unlift(Persona.unapply))
}

sealed abstract class PersonaName(val value: String)

object PersonaName {
  implicit def format[T]: Format[PersonaName] =
    Format(__.read[String].map(PersonaName(_)), new Writes[PersonaName] { def writes(o: PersonaName) = JsString(o.value) })

  case object DEVELOPER extends PersonaName("developer")
  case object TECHIE extends PersonaName("techie")
  case object ENTREPRENEUR extends PersonaName("entrepreneur")
  case object ARTIST extends PersonaName("artist")
  case object FOODIE extends PersonaName("foodie")
  case object SCIENCE_BUFF extends PersonaName("science_buff")
  case object FASHIONISTA extends PersonaName("fashionista")
  case object HEALTH_NUT extends PersonaName("health_nut")
  case object STUDENT extends PersonaName("student")
  case object INVESTOR extends PersonaName("investor")
  case object TRAVELER extends PersonaName("traveler")
  case object GAMER extends PersonaName("gamer")
  case object PARENT extends PersonaName("parent")
  case object ANIMAL_LOVER extends PersonaName("animal_lover")
  case object DEEP_THINKER extends PersonaName("deep_thinker")

  def apply(str: String) = {
    str.toLowerCase match {
      case DEVELOPER.value => DEVELOPER
      case TECHIE.value => TECHIE
      case ENTREPRENEUR.value => ENTREPRENEUR
      case ARTIST.value => ARTIST
      case FOODIE.value => FOODIE
      case SCIENCE_BUFF.value => SCIENCE_BUFF
      case FASHIONISTA.value => FASHIONISTA
      case HEALTH_NUT.value => HEALTH_NUT
      case STUDENT.value => STUDENT
      case INVESTOR.value => INVESTOR
      case TRAVELER.value => TRAVELER
      case GAMER.value => GAMER
      case PARENT.value => PARENT
      case ANIMAL_LOVER.value => ANIMAL_LOVER
      case DEEP_THINKER.value => DEEP_THINKER
    }
  }

  val allPersonas: Set[PersonaName] = Set(
    DEVELOPER,
    TECHIE,
    ENTREPRENEUR,
    ARTIST,
    FOODIE,
    SCIENCE_BUFF,
    FASHIONISTA,
    HEALTH_NUT,
    STUDENT,
    INVESTOR,
    TRAVELER,
    GAMER,
    PARENT,
    ANIMAL_LOVER,
    DEEP_THINKER
  )

  val personaLibraryNames: Map[PersonaName, String] = Map(
    DEVELOPER -> "Developer Resources",
    TECHIE -> "Techie Picks",
    ENTREPRENEUR -> "Entrepreneurial Articles",
    ARTIST -> "Artistic Ideas",
    FOODIE -> "Favorite Foods",
    SCIENCE_BUFF -> "Scientific Picks",
    FASHIONISTA -> "Fabulous Fashion",
    HEALTH_NUT -> "Healthy Habits",
    STUDENT -> "Student Resources",
    INVESTOR -> "Investing Ideas",
    TRAVELER -> "Travel Tips",
    GAMER -> "Gaming News",
    PARENT -> "Parenting Gems",
    ANIMAL_LOVER -> "Animal Antics",
    DEEP_THINKER -> "Deep Thoughts"
  )

  val personaKeeps: Map[PersonaName, PersonaKeep] = Map() // todo (aaron, ashley): pick out a default keep for each persona

}

object PersonaStates extends States[Persona]

@json case class PersonaKeep( // default keep infos for FTUE
  url: String,
  imagePath: Option[String] = None,
  imageWidth: Option[Int] = None,
  imageHeight: Option[Int] = None,
  noun: String,
  query: String,
  title: String,
  matches: JsObject,
  track: String)

object PersonaKeep { // todo (aaron): once every persona has a default keep, remove this!
  val default = PersonaKeep(
    url = "http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die",
    imagePath = Some("/img/guide/ted_jobs.jpg"),
    imageWidth = Some(480),
    imageHeight = Some(425),
    noun = "video",
    query = "steve+jobs",
    title = "Steve Jobs: How to live before you die | Talk Video | TED.com",
    matches = Json.obj("title" -> Json.toJson(Seq(Seq(0, 5), Seq(6, 4))), "url" -> Json.toJson(Seq(Seq(25, 5), Seq(31, 4)))),
    track = "steveJobsSpeech"
  )
}
