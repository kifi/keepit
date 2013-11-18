package com.keepit.heimdal

import com.keepit.model.{User, KifiVersion}
import com.keepit.search._
import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.{ExternalId, Id}
import play.api.mvc.AnyContent
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

  def searchPerformed(
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

    contextBuilder += ("queryCharacters", articleSearchResult.query.length)
    contextBuilder += ("queryWords", articleSearchResult.query.split("""\b""").length)
    contextBuilder += ("lang", articleSearchResult.lang.lang)

    contextBuilder += ("searchId", obfuscatedSearchId)
    contextBuilder += ("pageNumber", articleSearchResult.pageNumber)
    contextBuilder += ("maxHits", maxHits)
    contextBuilder += ("kifiResults", articleSearchResult.hits.length)
    contextBuilder += ("myHits", articleSearchResult.myTotal)
    contextBuilder += ("friendsHits", articleSearchResult.friendsTotal)
    contextBuilder += ("mayHaveMoreHits", articleSearchResult.mayHaveMoreHits)
    contextBuilder += ("processingTime", articleSearchResult.millisPassed)

    contextBuilder += ("defaultFilter", searchFilter.isDefault)
    contextBuilder += ("customFilter", searchFilter.isCustom)
    contextBuilder += ("includeMine", searchFilter.includeMine)
    contextBuilder += ("includeShared", searchFilter.includeShared)
    contextBuilder += ("includeFriends", searchFilter.includeFriends)
    contextBuilder += ("includeOthers", searchFilter.includeOthers)
    contextBuilder += ("filterByTimeRange", searchFilter.timeRange.isDefined)
    contextBuilder += ("filterByCollections", searchFilter.collections.isDefined)

    heimdal.trackEvent(UserEvent(request.userId.id, contextBuilder.build, EventType("search_performed"), articleSearchResult.time))
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
    searchEngine: SearchEngine,
    searchResultsClicked: Int,
    kifiCollapsed: Option[Boolean],
    time: DateTime) = {

    val obfuscatedSearchId = queryUUID.map(articleSearchResultStore.getInitialSearchId).map(obfuscate(_, userId))
    val contextBuilder = userEventContextBuilder()
    obfuscatedSearchId.map { id => contextBuilder += ("searchId", id) }
    searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("kifiResults", kifiResults)
    contextBuilder += ("kifiResultsClicked", kifiResultsClicked)
    contextBuilder += ("searchEngine", searchEngine.toString)
    contextBuilder += ("searchResultsClicked", searchResultsClicked)
    kifiCollapsed.foreach { collapsed => contextBuilder += ("kifiCollapsed", collapsed) }
    heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("search_ended"), time))
  }

  private def obfuscate(searchId: ExternalId[ArticleSearchResult], userId: Id[User]): String = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    val key = new SecretKeySpec(searchId.id.getBytes, algorithm)
    mac.init(key)
    Base64.encodeBase64String(mac.doFinal(userId.toString.getBytes()))
  }
}
