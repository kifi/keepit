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

  val keeps: Map[PersonaName, PersonaKeep] = Map(
    PersonaName.DEVELOPER -> PersonaKeep(
      title = "Top 10 Professional Sample Code Websites For Programmers",
      url = "http =//www.makeuseof.com/tag/top-10-professional-sample-code-websites-for-programmers/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/sample_websites.b87fdb6.jpg", 480, 394),
      noun = "blog post",
      query = "sample+codes",
      matches = createMatchJson(Seq((20, 6), (27, 4)), Seq((49, 6), (56, 4))),
      track = "sampleCodeDeveloperPersona"
    ),
    PersonaName.TECHIE -> PersonaKeep(
      title = "The Rise Of Nostalgia Tech",
      url = "http://www.fastcoexist.com/3020622/the-rise-of-nostalgia-tech",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/nostalgia_tech.0232540.jpg", 480, 358),
      noun = "article",
      query = "tech+trends",
      matches = createMatchJson(Seq((22, 4)), Seq((57, 4))),
      track = "nostalgiaTechiePersona"
    ),
    PersonaName.ENTREPRENEUR -> PersonaKeep(
      title = "11 Famous Entrepreneurs Share How They Overcame Their Biggest Failure",
      url = "http =//www.fastcompany.com/3029883/bottom-line/11-famous-entrepreneurs-share-how-they-overcame-their-biggest-failure",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/moving_cultures.542973a.jpg", 480, 372),
      noun = "blog post",
      query = "entrepreneurship",
      matches = createMatchJson(Seq((10, 13)), Seq((57, 13))),
      track = "biggestFailureEntrepreneurPersona"
    ),
    PersonaName.ARTIST -> PersonaKeep(
      title = "Six Things You May Not Know About the Louvre",
      url = "http://www.history.com/news/six-things-you-may-not-know-about-the-louvre",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/louvre.e60b47d.jpg", 480, 414),
      noun = "article",
      query = "the+louvre",
      matches = createMatchJson(Seq((38, 6)), Seq((66, 6))),
      track = "louvreArtistPersona"
    ),
    PersonaName.FOODIE -> PersonaKeep(
      title = "The Beginner’s Guide to Dim Sum",
      url = "http://luckypeach.com/the-beginners-field-guide-to-dim-sum/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/dim_sum.2facf8d.jpg", 480, 418),
      noun = "page",
      query = "dim+sum",
      matches = createMatchJson(Seq((24, 3), (28, 3)), Seq((51, 3), (55, 3))),
      track = "dimSumFoodiePersona"
    ),
    PersonaName.SCIENCE_BUFF -> PersonaKeep(
      title = "The Physics of Popcorn",
      url = "http=//www.nytimes.com/video/science/100000003510016/the-physics-of-popcorn.html",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/physics_popcorn.95caf57.jpg", 480, 368),
      noun = "video",
      query = "popcorn",
      matches = createMatchJson(Seq((15, 7)), Seq((68, 7))),
      track = "popcornSciencePersona"
    ),
    PersonaName.FASHIONISTA -> PersonaKeep(
      title = "7 Habits of Highly Stylish People",
      url = "http://stylecaster.com/the-7-habits-of-highly-stylish-people/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/style_habits.59313d9.jpg", 480, 366),
      noun = "page",
      query = "habits+of+stylish+people",
      matches = createMatchJson(Seq((2, 6), (19, 7), (27, 6)), Seq((29, 6), (46, 7), (54, 6))),
      track = "styleHabitsFashionPersona"
    ),
    PersonaName.HEALTH_NUT -> PersonaKeep(
      title = "We make exercise way too complicated. Here's how to get it right.",
      url = "http://www.vox.com/2014/12/17/7405451/best-workout-perfect-body",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/complicated_fitness.6fbb321.jpg", 480, 366),
      noun = "article",
      query = "exercise+and+working+out",
      matches = createMatchJson(Seq((8, 8)), Seq((43, 7))),
      track = "complicatedFitnessHealthPersona"
    ),
    PersonaName.STUDENT -> PersonaKeep(
      title = "This Diagram Shows Cornell's Revolutionary Method For Taking Notes",
      url = "http://www.businessinsider.com/cornell-perfect-way-to-take-notes-2014-12",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/cornell_note_taking.649e168.jpg", 480, 490),
      noun = "article",
      query = "cornell+note+taking",
      matches = createMatchJson(Seq((19, 7), (54, 6), (61, 5)), Seq((31, 7), (54, 4), (59, 5))),
      track = "cornellNotesStudentPersona"
    ),
    PersonaName.INVESTOR -> PersonaKeep(
      title = "What To Do With An Old Mutual Funds Investment",
      url = "http://www.forbes.com/sites/mitchelltuchman/2013/09/12/what-to-do-with-an-old-mutual-funds-investment/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/old_mutual_fund.fad0ee0.jpg", 480, 382),
      noun = "article",
      query = "old+mutual+funds",
      matches = createMatchJson(Seq((19, 3), (23, 6), (30, 5)), Seq((74, 3), (78, 6), (85, 5))),
      track = "oldMutualFundsInvestorPersona"
    ),
    PersonaName.TRAVELER -> PersonaKeep(
      title = "Lessons Learned From Visiting Every Country in the World",
      url = "http://www.fluentin3months.com/8-lessons-learned-from-visiting-every-country-in-the-world/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/fluent_in_3_months.0451c12.jpg", 480, 356),
      noun = "blog post",
      query = "visit+every+country",
      matches = createMatchJson(Seq((21, 5), (30, 5), (36, 7)), Seq((54, 5), (63, 5), (69, 7))),
      track = "visitEveryCountryTravelerPersona"
    ),
    PersonaName.GAMER -> PersonaKeep(
      title = "30 Best Text-Adventures/Interactive-Fiction Games Over 5 Decades",
      url = "http://www.gamingenthusiast.net/best-text-adventures-best-interactive-fiction-games/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/best_adventure_games.9038bc2.jpg", 480, 506),
      noun = "blog post",
      query = "best+adventure+games",
      matches = createMatchJson(Seq((3, 4), (13, 10), (44, 5)), Seq((32, 4), (42, 10), (78, 5))),
      track = "bestAdventureGamesGamerPersona"
    ),
    PersonaName.PARENT -> PersonaKeep(
      title = "What Happens to a Woman's Brain When She Becomes a Mother",
      url = "http://www.theatlantic.com/health/archive/2015/01/what-happens-to-a-womans-brain-when-she-becomes-a-mother/384179/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/mothers_brain.bff7abe.jpg", 480, 384),
      noun = "article",
      query = "mothers+brain",
      matches = createMatchJson(Seq((26, 5), (51, 6)), Seq((75, 5), (100, 6))),
      track = "motherBrainParentPersona"
    ),
    PersonaName.ANIMAL_LOVER -> PersonaKeep(
      title = "How a Kitty Walked 200 Miles Home: The Science of Your Cat’s Inner Compass",
      url = "http://science.time.com/2013/02/11/the-mystery-of-the-geolocating-cat/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/geolocating_cat.27ac02f.jpg", 480, 402),
      noun = "article",
      query = "geolocating+cat",
      matches = createMatchJson(Seq((55, 3)), Seq((54, 11), (66, 3))),
      track = "geolocatingCatAnimalLoverPersona"
    ),
    PersonaName.DEEP_THINKER -> PersonaKeep(
      title = "How Cultures Move Across Continents = Goats and Soda",
      url = "http=//www.npr.org/blogs/goatsandsoda/2014/08/01/336646562/how-cultures-move-across-continents",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/moving_cultures.542973a.jpg", 480, 412),
      noun = "blog post",
      query = "cultures+across+continents",
      matches = createMatchJson(Seq((4, 8), (8, 6), (25, 10)), Seq((63, 8), (77, 6), (84, 10))),
      track = "culturesDeepThinkerPersona"
    )
  )

  // each ordered pair (x, y) indicates to extension to bold starting at index x for length y
  private def createMatchJson(titleBolding: Seq[(Int, Int)], urlBolding: Seq[(Int, Int)]): JsObject = {
    val titleList = titleBolding.map { pair => Seq(pair._1, pair._2) }
    val urlList = urlBolding.map { pair => Seq(pair._1, pair._2) }
    Json.obj("title" -> Json.toJson(titleList), "url" -> Json.toJson(urlList))
  }

  // if for whatever reason, a default persona keep is not found, use this one
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
