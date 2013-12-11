package com.keepit.heimdal

import com.keepit.model.User
import com.keepit.search._
import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.{ExternalId, Id}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class SearchEngine(name: String) {
  override def toString = name
}

object SearchEngine {
  object Google extends SearchEngine("Google")
  object Kifi extends SearchEngine("Kifi")
  def get(name: String): SearchEngine = Seq(Kifi, Google).find(_.name.toLowerCase == name.toLowerCase) getOrElse { throw new Exception(s"Unknown search engine: $name") }
}

case class BasicSearchContext(
  origin: String,
  sessionId: String,
  refinements: Option[Int],
  uuid: ExternalId[ArticleSearchResult],
  searchExperiment: Option[Id[SearchConfigExperiment]],
  query: String,
  filterByPeople: Option[String],
  filterByTime: Option[String],
  kifiResults: Int,
  kifiExpanded: Boolean,
  kifiTime: Option[Int],
  kifiShownTime: Option[Int],
  thirdPartyShownTime: Option[Int],
  kifiResultsClicked: Int,
  thirdPartyResultsClicked: Int
)

object BasicSearchContext {
  implicit val reads: Reads[BasicSearchContext] = (
    (__ \ 'origin).read[String] and
    (__ \ 'pageSession).read[String] and
    (__ \ 'refinements).readNullable[Int] and
    (__ \ 'uuid).read(ExternalId.format[ArticleSearchResult]) and
    (__ \ 'experimentId).readNullable(Id.format[SearchConfigExperiment]) and
    (__ \ 'query).read[String] and
    (__ \ 'filter \ 'who).readNullable[String].fmap(filterByPeople) and
    (__ \ 'filter \ 'when).readNullable[String].fmap(filterByTime) and
    (__ \ 'kifiResults).read[Int] and
    (__ \ 'kifiExpanded).read[Boolean] and
    (__ \ 'kifiTime).readNullable[Int] and
    (__ \ 'kifiShownTime).readNullable[Int] and
    (__ \ 'thirdPartyShownTime).readNullable[Int] and
    (__ \ 'kifiResultsClicked).read[Int] and
    (__ \ 'thirdPartyResultsClicked).read[Int]
  )(BasicSearchContext.apply _)

  private def filterByPeople(who: Option[String]) = who collect {
    case "m" => "My Keeps"
    case "f" => "Friends' Keeps"
    case ids => "Custom"
  }

  private def filterByTime(when: Option[String]) = when collect {
    case "t" => "Today"
    case "y" => "Yesterday"
    case "w" => "Past Week"
    case "m" => "Past Month"
  }
}

@Singleton
class SearchAnalytics @Inject() (
  articleSearchResultStore: ArticleSearchResultStore,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  airbrake: AirbrakeNotifier) {

  def searched(
    userId: Id[User],
    time: DateTime,
    basicSearchContext: BasicSearchContext,
    endedWith: String,
    contextBuilder: HeimdalContextBuilder
  ) = {
    processBasicSearchContext(userId, basicSearchContext, contextBuilder)
    contextBuilder += ("endedWith", endedWith)
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.SEARCHED, time))
  }

  def clickedSearchResult(
    userId: Id[User],
    time: DateTime,
    basicSearchContext: BasicSearchContext,
    resultSource: SearchEngine,
    resultPosition: Int,
    result: Option[PersonalSearchResult],
    contextBuilder: HeimdalContextBuilder
  ) = {

    processBasicSearchContext(userId, basicSearchContext, contextBuilder)
    val queryTermsCount = contextBuilder.data.get("queryTerms").collect { case ContextDoubleData(count) => count.toInt }.get

    // Click Information

    contextBuilder += ("resultSource", resultSource.toString)
    contextBuilder += ("resultPosition", resultPosition)
    result.map { result =>
      contextBuilder += ("keepCount", result.count)
      contextBuilder += ("usersShown", result.users.length)
      contextBuilder += ("isOwn", result.isMyBookmark)
      contextBuilder += ("isFriends", !result.isMyBookmark && result.users.length > 0)
      contextBuilder += ("isOthers", result.users.length == 0)
      contextBuilder += ("isPrivate", result.isPrivate)
      contextBuilder += ("tags", result.hit.collections.map(_.length).getOrElse(0))
      contextBuilder += ("hasTitle", result.hit.title.isDefined)

      contextBuilder += ("titleMatches", result.hit.titleMatches.length)
      contextBuilder += ("urlMatches", result.hit.urlMatches.length)
      contextBuilder += ("titleMatchQueryRatio", result.hit.titleMatches.length.toDouble / queryTermsCount)
      contextBuilder += ("urlMatchQueryRatio", result.hit.urlMatches.length.toDouble / queryTermsCount)
    }

    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.CLICKED_SEARCH_RESULT, time))
  }

  private def processBasicSearchContext(userId: Id[User], searchContext: BasicSearchContext, contextBuilder: HeimdalContextBuilder): Unit = {
    val latestSearchResult = articleSearchResultStore.get(searchContext.uuid).get
    val initialSearchId = articleSearchResultStore.getInitialSearchId(latestSearchResult)
    val initialSearchResult = articleSearchResultStore.get(initialSearchId).get
    val queryTermsCount = initialSearchResult.query.split("""\b""").length

    // Search Context
    contextBuilder += ("origin", searchContext.origin)
    contextBuilder += ("sessionId", searchContext.sessionId)
    searchContext.refinements.foreach { refinements => contextBuilder += ("refinements", refinements) }
    contextBuilder += ("searchId", obfuscate(initialSearchId, userId))

    // Search Parameters
    searchContext.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    ("queryTerms", queryTermsCount)
    contextBuilder += ("lang", initialSearchResult.lang.lang)
    searchContext.filterByPeople.foreach { filter => contextBuilder += ("filterByPeople", filter) }
    searchContext.filterByTime.foreach { filter => contextBuilder += ("filterByTime", filter) }

    // Kifi Results

    contextBuilder += ("initialKifiResults", initialSearchResult.hits.length)
    contextBuilder += ("initialPersonalResults", initialSearchResult.myTotal)
    contextBuilder += ("initialFriendsResults", initialSearchResult.friendsTotal)
    contextBuilder += ("initialOthersResults", initialSearchResult.othersTotal)

    contextBuilder += ("kifiResults", searchContext.kifiResults)
    contextBuilder += ("kifiResultPages", latestSearchResult.pageNumber)
    contextBuilder += ("mayHaveMoreHits", latestSearchResult.mayHaveMoreHits)

    contextBuilder += ("kifiResultsClicked", searchContext.kifiResultsClicked)
    contextBuilder += ("thirdPartyResultsClicked", searchContext.thirdPartyResultsClicked)

    // Kifi Performance

    contextBuilder += ("kifiExpanded", searchContext.kifiExpanded)
    contextBuilder += ("kifiRelevant", initialSearchResult.toShow)
    contextBuilder += ("kifiLate", initialSearchResult.toShow && !searchContext.kifiExpanded)
    contextBuilder += ("kifiProcessingTime", initialSearchResult.millisPassed)
    searchContext.kifiTime.foreach { kifiLatency => contextBuilder += ("kifiLatency", kifiLatency) }
    searchContext.kifiShownTime.foreach { kifiShown => contextBuilder += ("kifiShownTime", kifiShown) }
    searchContext.thirdPartyShownTime.foreach { thirdPartyShown => contextBuilder += ("thirdPartyShownTime", thirdPartyShown) }
    for { kifiShown <- searchContext.kifiShownTime; thirdPartyShown <- searchContext.thirdPartyShownTime } yield { contextBuilder += ("kifiDelay", kifiShown - thirdPartyShown) }
    for { kifiShown <- searchContext.kifiShownTime; kifiLatency <- searchContext.kifiTime } yield { contextBuilder += ("kifiRenderingTime", kifiShown - kifiLatency)}


    // Data Consistency Checks
    if (searchContext.kifiResults != (latestSearchResult.previousHits + latestSearchResult.hits.length))
      airbrake.notify(AirbrakeError(new Exception(s"Inconsistent number of Kifi Results: received ${searchContext.kifiResults} but expected ${latestSearchResult.hits.length}")))
  }

  private def obfuscate(searchId: ExternalId[ArticleSearchResult], userId: Id[User]): String = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    val key = new SecretKeySpec(searchId.id.getBytes, algorithm)
    mac.init(key)
    Base64.encodeBase64String(mac.doFinal(userId.toString.getBytes()))
  }
}
