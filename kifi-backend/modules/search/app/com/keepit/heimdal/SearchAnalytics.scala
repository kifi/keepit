package com.keepit.heimdal

import com.keepit.model.{Collection, User}
import com.keepit.search._
import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.{ExternalId, Id}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import com.keepit.common.healthcheck.{AirbrakeNotifier}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.social.BasicUser

case class SearchEngine(name: String) {
  override def toString = name
}

object SearchEngine {
  object Google extends SearchEngine("google")
  object Kifi extends SearchEngine("kifi")
  def get(name: String): SearchEngine = Seq(Kifi, Google).find(_.name.toLowerCase == name.toLowerCase) getOrElse { throw new Exception(s"Unknown search engine: $name") }
}

case class BasicSearchContext(
  origin: String,
  sessionId: String,
  refinement: Option[Int],
  uuid: ExternalId[ArticleSearchResult],
  searchExperiment: Option[Id[SearchConfigExperiment]],
  query: String,
  filterByPeople: Option[String],
  filterByTime: Option[String],
  maxResults: Option[Int],
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
    (__ \ 'maxResults).readNullable[Int] and
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

case class KifiHitContext(
  isOwnKeep: Boolean,
  isPrivate: Boolean,
  keepCount: Int,
  keepers: Seq[ExternalId[User]],
  tags: Seq[ExternalId[Collection]],
  title: Option[String],
  titleMatches: Int,
  urlMatches: Int
)

object KifiHitContext {
  implicit val reads: Reads[KifiHitContext] = (
    (__ \ 'isMyBookmark).read[Boolean] and
    (__ \ 'isPrivate).read[Boolean] and
    (__ \ 'count).read[Int] and
    ((__ \ 'keepers).read[Seq[String]].fmap(_.map(ExternalId[User](_))) orElse (__ \ 'users).read[Seq[BasicUser]].fmap(_.map(_.externalId))) and
    ((__ \\ 'tags).readNullable[Seq[String]].fmap(_.toSeq.flatten.map(ExternalId[Collection](_)))) and
    ((__ \ 'title).read[String].fmap(Option(_)) orElse (__ \ 'bookmark \ 'title).readNullable[String]) and
    ((__ \ 'titleMatches).read[Int] orElse (__ \'bookmark \\ 'matches \ 'title).read[JsArray].fmap(_.value.length) orElse Reads.pure(0)) and
    ((__ \ 'urlMatches).read[Int] orElse (__ \'bookmark \\ 'matches \ 'url).read[JsArray].fmap(_.value.length) orElse Reads.pure(0))
  )(KifiHitContext.apply _)

}


@Singleton
class SearchAnalytics @Inject() (
  articleSearchResultStore: ArticleSearchResultStore,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  airbrake: AirbrakeNotifier) {

  def searched(
    userId: Id[User],
    searchedAt: DateTime,
    basicSearchContext: BasicSearchContext,
    endedWith: String,
    existingContext: HeimdalContext
  ) = {
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++= existingContext.data
    processBasicSearchContext(userId, basicSearchContext, contextBuilder)
    contextBuilder += ("endedWith", endedWith)
    heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.SEARCHED, searchedAt))
  }

  def clickedSearchResult(
    userId: Id[User],
    clickedAt: DateTime,
    basicSearchContext: BasicSearchContext,
    resultSource: SearchEngine,
    resultPosition: Int,
    kifiHitContext: Option[KifiHitContext],
    existingContext: HeimdalContext
  ) = {

    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++= existingContext.data
    processBasicSearchContext(userId, basicSearchContext, contextBuilder)

    // Click Information

    contextBuilder += ("resultSource", resultSource.toString)
    contextBuilder += ("resultPosition", resultPosition)
    kifiHitContext.map { hitContext =>
      contextBuilder += ("keep", keep(hitContext))
      contextBuilder += ("keepersShown", hitContext.keepers.length)
      contextBuilder += ("keepCount", hitContext.keepCount)
      contextBuilder += ("isPrivate", hitContext.isPrivate)

      contextBuilder += ("tags", hitContext.tags.length)
      contextBuilder += ("hasTitle", hitContext.title.isDefined)

      contextBuilder += ("titleMatches", hitContext.titleMatches)
      contextBuilder += ("urlMatches", hitContext.urlMatches)

      val queryTermsCount = contextBuilder.data.get("queryTerms").collect { case ContextDoubleData(count) => count.toInt }.get
      contextBuilder += ("titleMatchQueryRatio", hitContext.titleMatches.toDouble / queryTermsCount)
      contextBuilder += ("urlMatchQueryRatio", hitContext.urlMatches.toDouble / queryTermsCount)
    }

    heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.CLICKED_SEARCH_RESULT, clickedAt))
    if (resultSource == SearchEngine.Kifi) {
      contextBuilder += ("action", "clickedKifiResult")
      heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.USED_KIFI, clickedAt))
      heimdal.setUserProperties(userId, "lastClickedKifiResult" -> ContextDate(clickedAt))
    }
  }

  private def processBasicSearchContext(userId: Id[User], searchContext: BasicSearchContext, contextBuilder: HeimdalContextBuilder): Unit = {
    val latestSearchResult = articleSearchResultStore.get(searchContext.uuid).get
    val initialSearchId = articleSearchResultStore.getInitialSearchId(latestSearchResult)
    val initialSearchResult = articleSearchResultStore.get(initialSearchId).get

    // Search Context
    contextBuilder += ("origin", searchContext.origin)
    contextBuilder += ("source", getSource(searchContext.origin))
    contextBuilder += ("sessionId", searchContext.sessionId)
    searchContext.refinement.foreach { refinement => contextBuilder += ("refinement", refinement) }
    contextBuilder += ("searchId", obfuscate(initialSearchId, userId))

    // Search Parameters
    searchContext.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("queryTerms", initialSearchResult.query.split(" ").length)
    contextBuilder += ("queryCharacters", initialSearchResult.query.length)
    contextBuilder += ("language", initialSearchResult.lang)
    searchContext.filterByPeople.foreach { filter => contextBuilder += ("filterByPeople", filter) }
    searchContext.filterByTime.foreach { filter => contextBuilder += ("filterByTime", filter) }

    // Kifi Results

    val topKeeps = initialSearchResult.hits.map(keep)
    contextBuilder += ("topKifiResults", topKeeps.length)
    contextBuilder += ("topKeeps", topKeeps)
    contextBuilder += ("ownTopKeeps", topKeeps.count(_ == own))
    contextBuilder += ("friendsTopKeeps", topKeeps.count(_ == friends))
    contextBuilder += ("othersTopKeeps", topKeeps.count(_ == others))

    searchContext.maxResults.foreach { maxResults =>
      val initialKeeps = topKeeps take maxResults
      contextBuilder += ("maxResults", maxResults)
      contextBuilder += ("initialKifiResults", initialKeeps.length)
      contextBuilder += ("initialKeeps", initialKeeps)
      contextBuilder += ("initialOwnKeeps", initialKeeps.count(_ == own))
      contextBuilder += ("initialFriendsKeeps", initialKeeps.count(_ == friends))
      contextBuilder += ("initialOthersKeeps", initialKeeps.count(_ == others))
    }

    contextBuilder += ("moreResultsRequests", latestSearchResult.pageNumber)
    contextBuilder += ("displayedKifiResults", searchContext.kifiResults)
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

  private def keep(kifiHitContext: KifiHitContext): String = {
    if (kifiHitContext.isOwnKeep) own
    else if (kifiHitContext.keepers.length > 0) friends
    else others
  }

  private def keep(hit: ArticleHit): String = {
    if (hit.isMyBookmark) own
    else if (hit.users.length > 0) friends
    else others
  }

  private def getSource(origin: String): String = origin.split('.').reverse(1)

  private val own = "own"
  private val friends = "friends"
  private val others = "others"
}
