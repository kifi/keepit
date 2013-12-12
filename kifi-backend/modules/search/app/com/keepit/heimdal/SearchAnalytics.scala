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
  kifiResultsClicked: Option[Int],
  thirdPartyResultsClicked: Option[Int]
)

object BasicSearchContext {
  implicit val reads: Reads[BasicSearchContext] = (
    (__ \ 'origin).read[String] and
    (__ \ 'pageSession).readNullable[String].fmap(_.getOrElse("")) and
    (__ \ 'refinements).readNullable[Int] and
    ((__ \ 'uuid).read(ExternalId.format[ArticleSearchResult]) orElse (__ \ 'queryUUID).read(ExternalId.format[ArticleSearchResult])) and
    (__ \ 'experimentId).readNullable(Id.format[SearchConfigExperiment]) and
    (__ \ 'query).read[String] and
    (__ \\ 'who).readNullable[String].fmap(filterByPeople) and
    (__ \\ 'when).readNullable[String].fmap(filterByTime) and
    (__ \ 'kifiResults).read[Int] and
    ((__ \ 'kifiExpanded).read[Boolean] orElse (__ \ 'kifiCollapsed).read[Boolean].fmap(!_)) and
    (__ \ 'kifiTime).readNullable[Int] and
    (__ \ 'kifiShownTime).readNullable[Int] and
    (__ \ 'thirdPartyShownTime).readNullable[Int] and
    (__ \ 'kifiResultsClicked).readNullable[Int] and
    (__ \ 'thirdPartyResultsClicked).readNullable[Int]
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
    result: Option[KifiSearchHit],
    contextBuilder: HeimdalContextBuilder
  ) = {

    processBasicSearchContext(userId, basicSearchContext, contextBuilder)

    // Click Information

    contextBuilder += ("resultSource", resultSource.toString)
    contextBuilder += ("resultPosition", resultPosition)
    result.map { result =>
      val hit = result.bookmark
      contextBuilder += ("keepCount", result.count)
      contextBuilder += ("usersShown", result.users.length)
      contextBuilder += ("isOwn", result.isMyBookmark)
      contextBuilder += ("isFriends", !result.isMyBookmark && result.users.length > 0)
      contextBuilder += ("isOthers", result.users.length == 0)
      contextBuilder += ("isPrivate", result.isPrivate)
      contextBuilder += ("tags", hit.collections.map(_.length).getOrElse(0))
      contextBuilder += ("hasTitle", hit.title.isDefined)

      contextBuilder += ("titleMatches", hit.titleMatches.length)
      contextBuilder += ("urlMatches", hit.urlMatches.length)

      val queryTermsCount = contextBuilder.data.get("queryTerms").collect { case ContextDoubleData(count) => count.toInt }.get
      contextBuilder += ("titleMatchQueryRatio", hit.titleMatches.length.toDouble / queryTermsCount)
      contextBuilder += ("urlMatchQueryRatio", hit.urlMatches.length.toDouble / queryTermsCount)
    }

    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.CLICKED_SEARCH_RESULT, time))
  }

  private def processBasicSearchContext(userId: Id[User], searchContext: BasicSearchContext, contextBuilder: HeimdalContextBuilder): Unit = {
    val latestSearchResult = articleSearchResultStore.get(searchContext.uuid).get
    val initialSearchId = articleSearchResultStore.getInitialSearchId(latestSearchResult)
    val initialSearchResult = articleSearchResultStore.get(initialSearchId).get

    // Search Context
    contextBuilder += ("origin", searchContext.origin)
    contextBuilder += ("sessionId", searchContext.sessionId)
    searchContext.refinements.foreach { refinements => contextBuilder += ("refinements", refinements) }
    contextBuilder += ("searchId", obfuscate(initialSearchId, userId))

    // Search Parameters
    searchContext.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("queryTerms", initialSearchResult.query.split("""\b""").length)
    contextBuilder += ("queryCharacters", initialSearchResult.query.length)
    contextBuilder += ("lang", initialSearchResult.lang.lang)
    searchContext.filterByPeople.foreach { filter => contextBuilder += ("filterByPeople", filter) }
    searchContext.filterByTime.foreach { filter => contextBuilder += ("filterByTime", filter) }

    // Kifi Results

    contextBuilder += ("kifiResults", searchContext.kifiResults)
    contextBuilder += ("moreResultsRequests", latestSearchResult.pageNumber)
    searchContext.kifiResultsClicked.foreach { count => contextBuilder += ("kifiResultsClicked", count) }
    searchContext.thirdPartyResultsClicked.foreach { count => contextBuilder += ("thirdPartyResultsClicked", count) }

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
  }

  private def obfuscate(searchId: ExternalId[ArticleSearchResult], userId: Id[User]): String = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    val key = new SecretKeySpec(searchId.id.getBytes, algorithm)
    mac.init(key)
    Base64.encodeBase64String(mac.doFinal(userId.toString.getBytes()))
  }
}
