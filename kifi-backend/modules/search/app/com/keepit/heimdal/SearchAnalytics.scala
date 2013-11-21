package com.keepit.heimdal

import com.keepit.model.{User, KifiVersion}
import com.keepit.search._
import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.{ExternalId, Id}
import play.api.mvc.{RequestHeader, AnyContent}
import com.keepit.common.controller.AuthenticatedRequest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime

case class SearchEngine(name: String) {
  override def toString = name
}

object SearchEngine {
  object Google extends SearchEngine("Google")
  object Kifi extends SearchEngine("Kifi")
  def get(name: String): SearchEngine = Seq(Kifi, Google).find(_.name.toLowerCase == name.toLowerCase) getOrElse { throw new Exception(s"Unknown search engine: $name") }
}

@Singleton
class SearchAnalytics @Inject() (
  articleSearchResultStore: ArticleSearchResultStore,
  userEventContextBuilder: EventContextBuilderFactory,
  heimdal: HeimdalServiceClient) {

  def performedSearch(
    request: AuthenticatedRequest[AnyContent],
    kifiVersion: Option[KifiVersion],
    maxHits: Int,
    searchFilter: SearchFilter,
    searchExperiment: Option[Id[SearchConfigExperiment]],
    articleSearchResult: ArticleSearchResult) = {

    val obfuscatedSearchId = obfuscate(articleSearchResultStore.getInitialSearchId(articleSearchResult), request.userId)
    val contextBuilder = userEventContextBuilder(Some(request))

    kifiVersion.foreach { version => contextBuilder += ("extVersion", version.toString) }
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }

    contextBuilder += ("queryTerms", articleSearchResult.query.split("""\b""").length)
    contextBuilder += ("lang", articleSearchResult.lang.lang)

    contextBuilder += ("searchId", obfuscatedSearchId)
    contextBuilder += ("pageNumber", articleSearchResult.pageNumber)
    contextBuilder += ("isInitialSearch", articleSearchResult.pageNumber == 0)
    contextBuilder += ("mayHaveMoreHits", articleSearchResult.mayHaveMoreHits)
    contextBuilder += ("processingTime", articleSearchResult.millisPassed)

    contextBuilder += ("kifiResults", articleSearchResult.hits.length)
    contextBuilder += ("maxHits", maxHits)
    contextBuilder += ("myHits", articleSearchResult.myTotal)
    contextBuilder += ("friendsHits", articleSearchResult.friendsTotal)
    contextBuilder += ("othersHits", articleSearchResult.othersTotal)

    contextBuilder += ("defaultFilter", searchFilter.isDefault)
    contextBuilder += ("customFilter", searchFilter.isCustom)
    contextBuilder += ("includeMine", searchFilter.includeMine)
    contextBuilder += ("includeShared", searchFilter.includeShared)
    contextBuilder += ("includeFriends", searchFilter.includeFriends)
    contextBuilder += ("includeOthers", searchFilter.includeOthers)
    contextBuilder += ("filterByTimeRange", searchFilter.timeRange.isDefined)
    contextBuilder += ("filterByTags", searchFilter.collections.isDefined)

    heimdal.trackEvent(UserEvent(request.userId.id, contextBuilder.build, EventType("performed_search"), articleSearchResult.time))
  }

  def searchResultClicked(
    userId: Id[User],
    queryUUID: Option[ExternalId[ArticleSearchResult]],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    resultSource: SearchEngine,
    resultPosition: Int,
    kifiResults: Int,
    kifiCollapsed: Option[Boolean],
    time: DateTime) = {

    val obfuscatedSearchId = queryUUID.map(articleSearchResultStore.getInitialSearchId).map(obfuscate(_, userId))
    val contextBuilder = userEventContextBuilder()
    obfuscatedSearchId.map { id => contextBuilder += ("searchId", id) }
    contextBuilder += ("searchEngine", resultSource.toString)
    contextBuilder += ("resultPosition", resultPosition)
    contextBuilder += ("kifiResults", kifiResults)
    kifiCollapsed.foreach { collapsed => contextBuilder += ("kifiCollapsed", collapsed) }
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("search_result_clicked"), time))
  }

  def kifiResultClicked(
    userId: Id[User],
    queryUUID: Option[ExternalId[ArticleSearchResult]],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    resultPosition: Int,
    bookmarkCount: Option[Int],
    usersShown: Option[Int],
    isUserKeep: Boolean,
    isPrivate: Option[Boolean],
    kifiResults: Int,
    kifiCollapsed: Option[Boolean],
    time: DateTime) = {

    val obfuscatedSearchId = queryUUID.map(articleSearchResultStore.getInitialSearchId).map(obfuscate(_, userId))
    val contextBuilder = userEventContextBuilder()
    obfuscatedSearchId.map { id => contextBuilder += ("searchId", id) }
    contextBuilder += ("resultPosition", resultPosition)
    bookmarkCount.foreach { count => contextBuilder += ("bookmarkCount", count) }
    usersShown.foreach { count => contextBuilder += ("usersShown", count) }
    contextBuilder += ("isUserKeep", isUserKeep)
    isPrivate.foreach { priv => contextBuilder += ("isPrivate", priv) }
    contextBuilder += ("kifiResults", kifiResults)
    kifiCollapsed.foreach { collapsed => contextBuilder += ("kifiCollapsed", collapsed) }
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("kifi_result_clicked"), time))
  }

  def searchEnded(
    userId: Id[User],
    queryUUID: Option[ExternalId[ArticleSearchResult]],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    kifiResults: Int,
    kifiResultsClicked: Int,
    origin: String,
    searchResultsClicked: Int,
    kifiCollapsed: Option[Boolean],
    time: DateTime) = {

    val obfuscatedSearchId = queryUUID.map(articleSearchResultStore.getInitialSearchId).map(obfuscate(_, userId))
    val contextBuilder = userEventContextBuilder()
    obfuscatedSearchId.map { id => contextBuilder += ("searchId", id) }
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("kifiResults", kifiResults)
    contextBuilder += ("kifiResultsClicked", kifiResultsClicked)
    contextBuilder += ("origin", origin)
    contextBuilder += ("searchResultsClicked", searchResultsClicked)
    kifiCollapsed.foreach { collapsed => contextBuilder += ("kifiCollapsed", collapsed) }
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("search_ended"), time))
  }

  def endedSearch(
    request: RequestHeader,
    userId: Id[User],
    time: DateTime,
    origin: String,
    uuid: ExternalId[ArticleSearchResult],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    kifiResults: Int,
    kifiCollapsed: Boolean,
    kifiTime: Int,
    referenceTime: Option[Int],
    otherResultsClicked: Int,
    kifiResultsClicked: Int
    ) = {

    val contextBuilder = searchContextBuilder(request, userId, origin, uuid, searchExperiment, kifiResults, kifiCollapsed, kifiTime, referenceTime)

    // Click Summary

    contextBuilder += ("kifiResultsClicked", kifiResultsClicked)
    contextBuilder += ("otherResultsClicked", otherResultsClicked)

    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("ended_search"), time))
  }

  def clickedSearchResult(
    request: RequestHeader,
    userId: Id[User],
    time: DateTime,
    origin: String,
    uuid: ExternalId[ArticleSearchResult],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    query: String,
    kifiResults: Int,
    kifiCollapsed: Boolean,
    kifiTime: Int,
    referenceTime: Option[Int],
    resultSource: SearchEngine,
    resultPosition: Int,
    result: Option[PersonalSearchResult]) = {

    val contextBuilder = searchContextBuilder(request, userId, origin, uuid, searchExperiment, kifiResults, kifiCollapsed, kifiTime, referenceTime)

    // Click Information

    contextBuilder += ("resultSource", resultSource.toString)
    contextBuilder += ("resultPosition", resultPosition)
    result.map { result =>
      contextBuilder += ("bookmarkCount", result.count)
      contextBuilder += ("usersShown", result.users.length)
      contextBuilder += ("isUserKeep", result.isMyBookmark)
      contextBuilder += ("isPrivate", result.isPrivate)
      contextBuilder += ("collectionCount", result.hit.collections.map(_.length).getOrElse(0))
      contextBuilder += ("hasTitle", result.hit.title.isDefined)

      val queryTerms = query.split("""\b""").length
      contextBuilder += ("titleMatches", result.hit.titleMatches.length)
      contextBuilder += ("urlMatches", result.hit.urlMatches.length)
      contextBuilder += ("titleMatchQueryRatio", result.hit.titleMatches.length.toDouble / queryTerms)
      contextBuilder += ("urlMatchQueryRatio", result.hit.urlMatches.length.toDouble / queryTerms)
    }

    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("clicked_search_result"), time))
  }

  private def obfuscate(searchId: ExternalId[ArticleSearchResult], userId: Id[User]): String = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    val key = new SecretKeySpec(searchId.id.getBytes, algorithm)
    mac.init(key)
    Base64.encodeBase64String(mac.doFinal(userId.toString.getBytes()))
  }

  private def searchContextBuilder(
    request: RequestHeader,
    userId: Id[User],
    origin: String,
    uuid: ExternalId[ArticleSearchResult],
    searchExperiment: Option[Id[SearchConfigExperiment]],
    kifiResults: Int,
    kifiCollapsed: Boolean,
    kifiTime: Int,
    referenceTime: Option[Int]
  ): EventContextBuilder = {

    val initialSearchId = articleSearchResultStore.getInitialSearchId(uuid)
    val initialSearchResult = articleSearchResultStore.get(initialSearchId).get

    val contextBuilder = userEventContextBuilder(Some(request))

    // Search Context
    contextBuilder += ("searchId", obfuscate(initialSearchId, userId))
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("origin", origin)
    ("queryTerms", initialSearchResult.query.split("""\b""").length)

    // Kifi Performances
    contextBuilder += ("kifiResults", kifiResults)
    contextBuilder += ("kifiCollapsed", kifiCollapsed)
    contextBuilder += ("kifiRelevant", initialSearchResult.toShow)
    contextBuilder += ("kifiLate", kifiCollapsed && initialSearchResult.toShow)
    contextBuilder += ("kifiDeliveryTime", kifiTime)
    referenceTime.foreach { refTime => contextBuilder += ("3rdPartyDeliveryTime", refTime) }
    contextBuilder += ("isInitialSearch", uuid == initialSearchId)

    contextBuilder
  }
}
