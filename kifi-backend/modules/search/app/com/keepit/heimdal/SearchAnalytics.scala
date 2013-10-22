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
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}

@Singleton
class SearchAnalytics @Inject() (
  articleSearchResultStore: ArticleSearchResultStore,
  userEventContextBuilder: UserEventContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  airbrake: AirbrakeNotifier) {

  case class SearchEngine(name: String) {
    override def toString = name
  }
  object Google extends SearchEngine("Google")
  object Kifi extends SearchEngine("Kifi")
  object SearchEngine {
    def get(name: String): SearchEngine = Seq(Kifi, Google).find(_.name.toLowerCase == name.toLowerCase) getOrElse {
      airbrake.notify(AirbrakeError(message = Some(s"Unknown Search Engine: ${name}")))
      SearchEngine(name)
    }
  }

  def searchPerformed(
    request: AuthenticatedRequest[AnyContent],
    kifiVersion: Option[KifiVersion],
    maxHits: Int,
    searchFilter: SearchFilter,
    searchExperiment: Option[Id[SearchConfigExperiment]],
    articleSearchResult: ArticleSearchResult) = {

    val obfuscatedSearchId = obfuscate(articleSearchResultStore.getSearchId(articleSearchResult), request.userId)
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
    contextBuilder += ("millisPassed", articleSearchResult.millisPassed)

    contextBuilder += ("defaultFilter", searchFilter.isDefault)
    contextBuilder += ("customFilter", searchFilter.isCustom)
    contextBuilder += ("includeMine", searchFilter.includeMine)
    contextBuilder += ("includeShared", searchFilter.includeShared)
    contextBuilder += ("includeFriends", searchFilter.includeFriends)
    contextBuilder += ("includeOthers", searchFilter.includeOthers)
    contextBuilder += ("filterByTimeRange", searchFilter.timeRange.isDefined)
    contextBuilder += ("filterByCollections", searchFilter.collections.isDefined)

    heimdal.trackEvent(UserEvent(request.userId.id, contextBuilder.build, UserEventType("search_performed"), articleSearchResult.time))
  }

  def searchResultClicked(resultClicked: ResultClicked) = {

    val obfuscatedSearchId = resultClicked.queryUUID.map(articleSearchResultStore.getSearchId).map(obfuscate(_, resultClicked.userId))
    val contextBuilder = userEventContextBuilder()
    contextBuilder += ("searchId", obfuscatedSearchId.getOrElse(""))
    contextBuilder += ("resultSource", SearchEngine.get(resultClicked.resultSource).toString)
    contextBuilder += ("resultPosition", resultClicked.resultPosition)
    contextBuilder += ("kifiResults", resultClicked.kifiResults)
    resultClicked.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    heimdal.trackEvent(UserEvent(resultClicked.userId.id, contextBuilder.build, UserEventType("search_result_clicked"), resultClicked.time))
  }

  def searchEnded(searchEnded: SearchEnded) = {

    val obfuscatedSearchId = searchEnded.queryUUID.map(articleSearchResultStore.getSearchId).map(obfuscate(_, searchEnded.userId))
    val contextBuilder = userEventContextBuilder()
    contextBuilder += ("searchId", obfuscatedSearchId.getOrElse(""))
    searchEnded.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
    contextBuilder += ("kifiResults", searchEnded.kifiResults)
    contextBuilder += ("kifiResultsClicked", searchEnded.kifiResultsClicked)
    contextBuilder += ("googleResultsClicked", searchEnded.googleResultsClicked)
    heimdal.trackEvent(UserEvent(searchEnded.userId.id, contextBuilder.build, UserEventType("search_ended"), searchEnded.time))
  }

  private def obfuscate(searchId: ExternalId[ArticleSearchResult], userId: Id[User]): String = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    val key = new SecretKeySpec(searchId.id.getBytes, algorithm)
    mac.init(key)
    Base64.encodeBase64String(mac.doFinal(userId.toString.getBytes()))
  }
}
