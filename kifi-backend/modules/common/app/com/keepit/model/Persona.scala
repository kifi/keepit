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
      url = "http://www.makeuseof.com/tag/top-10-professional-sample-code-websites-for-programmers/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/samplecode_600x600.663cd12.png", 480, 394),
      noun = "blog post",
      query = "sample+codes",
      matches = createMatchJson(Seq((20, 6), (27, 4)), Seq((49, 6), (56, 4))),
      track = "sampleCodeDeveloperPersona"
    ),
    PersonaName.TECHIE -> PersonaKeep(
      title = "You Should Be Terrified of Superintelligent Machines",
      url = "http://www.slate.com/articles/technology/future_tense/2014/09/will_artificial_intelligence_turn_on_us_robots_are_nothing_like_humans_and.single.html",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/superintelligentMachines_600x600.f6d6673.png", 480, 358),
      noun = "article",
      query = "artificial+intelligence",
      matches = createMatchJson(Seq((32, 11), (44, 8)), Seq((78, 8))),
      track = "attackingRobotsTechiePersona"
    ),
    PersonaName.ENTREPRENEUR -> PersonaKeep(
      title = "11 Famous Entrepreneurs Share How They Overcame Their Biggest Failure",
      url = "http://www.fastcompany.com/3029883/bottom-line/11-famous-entrepreneurs-share-how-they-overcame-their-biggest-failure",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/entrepreneurFail_600x600.17a6c70.png", 480, 372),
      noun = "blog post",
      query = "entrepreneurship",
      matches = createMatchJson(Seq((10, 13)), Seq((57, 13))),
      track = "biggestFailureEntrepreneurPersona"
    ),
    PersonaName.ARTIST -> PersonaKeep(
      title = "Six Things You May Not Know About the Louvre",
      url = "http://www.history.com/news/six-things-you-may-not-know-about-the-louvre",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/louvre_600x600.19387a6.png", 480, 414),
      noun = "article",
      query = "the+louvre",
      matches = createMatchJson(Seq((38, 6)), Seq((66, 6))),
      track = "louvreArtistPersona"
    ),
    PersonaName.FOODIE -> PersonaKeep(
      title = "The Beginner’s Guide to Dim Sum",
      url = "http://luckypeach.com/the-beginners-field-guide-to-dim-sum/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/dimsum_600x600.7548484.png", 480, 418),
      noun = "page",
      query = "dim+sum",
      matches = createMatchJson(Seq((24, 3), (28, 3)), Seq((51, 3), (55, 3))),
      track = "dimSumFoodiePersona"
    ),
    PersonaName.SCIENCE_BUFF -> PersonaKeep(
      title = "How the Universe Made the Stuff That Made Us",
      url = "http://nautil.us/blog/how-the-universe-made-the-stuff-that-made-us",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/universeMadeUs_600x600.51e13a0.png", 480, 368),
      noun = "article",
      query = "universe",
      matches = createMatchJson(Seq((8, 8)), Seq((30, 8))),
      track = "universeSciencePersona"
    ),
    PersonaName.FASHIONISTA -> PersonaKeep(
      title = "Isaac Mizrahi on Fashion & Creativity",
      url = "http://www.ted.com/talks/isaac_mizrahi_on_fashion_and_creativity?language=en",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/isaacMizrahi_600x600.ec5f66b.png", 480, 366),
      noun = "video",
      query = "isaac+mizrahi",
      matches = createMatchJson(Seq((0, 5), (6, 7)), Seq((25, 5), (31, 7))),
      track = "isaacMizrahiFashionPersona"
    ),
    PersonaName.HEALTH_NUT -> PersonaKeep(
      title = "Where does the fat go when you lose it?",
      url = "http://www.washingtonpost.com/news/to-your-health/wp/2014/12/16/where-does-the-fat-go-when-you-lose-it-hint-the-fat-fairy-is-not-involved/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/fatFairy_600x600.f94d22e.png", 480, 366),
      noun = "article",
      query = "losing+fat",
      matches = createMatchJson(Seq((15, 3), (31, 4)), Seq((79, 3), (95, 4), (112, 3))),
      track = "fatFairyHealthPersona"
    ),
    PersonaName.STUDENT -> PersonaKeep(
      title = "This Diagram Shows Cornell's Revolutionary Method For Taking Notes",
      url = "http://www.businessinsider.com/cornell-perfect-way-to-take-notes-2014-12",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/noteTaking_600x600.dcf14c2.png", 480, 490),
      noun = "article",
      query = "cornell+note+taking",
      matches = createMatchJson(Seq((19, 7), (54, 6), (61, 5)), Seq((31, 7), (54, 4), (59, 5))),
      track = "cornellNotesStudentPersona"
    ),
    PersonaName.INVESTOR -> PersonaKeep(
      title = "The Power of a Zero-Sum Budget",
      url = "http://lifehacker.com/the-power-of-a-zero-sum-budget-1443100021",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/zeroSumBudget_600x600.ce7b012.png", 480, 382),
      noun = "article",
      query = "zero+sum+budget",
      matches = createMatchJson(Seq((15, 4), (20, 3), (24, 6)), Seq((37, 4), (42, 3), (46, 6))),
      track = "zeroSumInvestorPersona"
    ),
    PersonaName.TRAVELER -> PersonaKeep(
      title = "Lessons Learned From Visiting Every Country in the World",
      url = "http://www.fluentin3months.com/8-lessons-learned-from-visiting-every-country-in-the-world/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/visitEveryCountry_600x600.efcd87e.png", 480, 356),
      noun = "blog post",
      query = "visit+every+country",
      matches = createMatchJson(Seq((21, 5), (30, 5), (36, 7)), Seq((54, 5), (63, 5), (69, 7))),
      track = "visitEveryCountryTravelerPersona"
    ),
    PersonaName.GAMER -> PersonaKeep(
      title = "The Board Game of the Alpha Nerds",
      url = "http://fivethirtyeight.com/features/designing-the-best-board-game-on-the-planet/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/boardGame_600x600.86580e6.png", 480, 506),
      noun = "blog post",
      query = "board+games",
      matches = createMatchJson(Seq((5, 5), (11, 4)), Seq((55, 5), (61, 4))),
      track = "alphaNerdGamerPersona"
    ),
    PersonaName.PARENT -> PersonaKeep(
      title = "Should You Teach Your Kids to Share",
      url = "http://www.popsugar.com/moms/Should-You-Teach-Kids-Share-27333250",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/childrenShare_600x600.5a63314.png", 480, 384),
      noun = "blog post",
      query = "kids+and+sharing",
      matches = createMatchJson(Seq((22, 4), (30, 5)), Seq((46, 4), (51, 5))),
      track = "shareKidsParentPersona"
    ),
    PersonaName.ANIMAL_LOVER -> PersonaKeep(
      title = "How a Kitty Walked 200 Miles Home: The Science of Your Cat’s Inner Compass",
      url = "http://science.time.com/2013/02/11/the-mystery-of-the-geolocating-cat/",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/geolocatingCat_600x600.dc3975b.png", 480, 402),
      noun = "article",
      query = "geolocating+cat",
      matches = createMatchJson(Seq((55, 3)), Seq((54, 11), (66, 3))),
      track = "geolocatingCatAnimalLoverPersona"
    ),
    PersonaName.DEEP_THINKER -> PersonaKeep(
      title = "What happened to Downtime?",
      url = "http://99u.com/articles/6947/what-happened-to-downtime-the-extinction-of-deep-thinking-sacred-space",
      image = PersonaKeepImageInfo("//d1dwdv9wd966qu.cloudfront.net/img/guide/downtime_600x600.2fb49f2.png", 480, 412),
      noun = "blog post",
      query = "downtime",
      matches = createMatchJson(Seq((17, 8)), Seq((46, 8))),
      track = "downtimeDeepThinkerPersona"
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
