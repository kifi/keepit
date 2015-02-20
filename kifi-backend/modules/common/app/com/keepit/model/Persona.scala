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

  val libraryNames: Map[PersonaName, String] = Map(
    PersonaName.DEVELOPER -> "Developer Resources",
    PersonaName.TECHIE -> "Techie Picks",
    PersonaName.ENTREPRENEUR -> "Entrepreneurial Articles",
    PersonaName.ARTIST -> "Artistic Ideas",
    PersonaName.FOODIE -> "Favorite Foods",
    PersonaName.SCIENCE_BUFF -> "Scientific Picks",
    PersonaName.FASHIONISTA -> "Fabulous Fashion",
    PersonaName.HEALTH_NUT -> "Healthy Habits",
    PersonaName.STUDENT -> "Student Resources",
    PersonaName.INVESTOR -> "Investing Ideas",
    PersonaName.TRAVELER -> "Travel Tips",
    PersonaName.GAMER -> "Gaming News",
    PersonaName.PARENT -> "Parenting Gems",
    PersonaName.ANIMAL_LOVER -> "Animal Antics",
    PersonaName.DEEP_THINKER -> "Deep Thoughts")

  val names: Set[PersonaName] = libraryNames.keySet

  // todo (aaron, ashley): pick out a keep for each persona
  val keeps: Map[PersonaName, PersonaKeep] = Map()

  // todo (aaron): once every persona has a keep, remove this!
  val defaultKeep = PersonaKeep(
    url = "http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die",
    image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/ted_jobs.7878954.jpg", 480, 425),
    noun = "video",
    query = "steve+jobs",
    title = "Steve Jobs: How to live before you die | Talk Video | TED.com",
    matches = Json.obj("title" -> Json.toJson(Seq(Seq(0, 5), Seq(6, 4))), "url" -> Json.toJson(Seq(Seq(25, 5), Seq(31, 4)))),
    track = "steveJobsSpeech")
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

}

object PersonaStates extends States[Persona]

@json case class PersonaKeepImageInfo(
  url: String, // absolute or relative to https://www.kifi.com
  width: Int, // image's natural dimensions; will be displayed at half size (for hi-res displays)
  height: Int)

@json case class PersonaKeep( // for keeper FTUE
  url: String,
  image: PersonaKeepImageInfo,
  noun: String,
  query: String,
  title: String,
  matches: JsObject,
  track: String)
