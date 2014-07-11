package com.keepit.common.routes

import com.keepit.common.db.{ SequenceNumber, ExternalId, Id, State }
import com.keepit.heimdal.BasicDelightedAnswer
import com.keepit.model._
import com.keepit.search.SearchConfigExperiment
import java.net.URLEncoder
import com.keepit.common.strings.UTF8
import com.keepit.search.message.ThreadContent
import com.keepit.eliza.model.MessageHandle
import com.keepit.cortex.core.{ StatModel, ModelVersion }
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.mail.EmailAddress

trait Service

case class ServiceRoute(method: Method, path: String, params: Param*) {
  def url = {
    val paramString = params.collect {
      case Param(key, ParamValue(Some(value))) =>
        URLEncoder.encode(key, UTF8) + "=" + URLEncoder.encode(value, UTF8)
    }.mkString("&")
    if (paramString.isEmpty) path else path + "?" + paramString
  }
}

case class Param(key: String, value: ParamValue = ParamValue(None)) {
  override def toString(): String = s"key->${value.value.getOrElse("")}"
}

case class ParamValue(value: Option[String])

object ParamValue {
  implicit def stringToParam(i: String) = ParamValue(Some(i))
  implicit def longToParam(i: Long) = ParamValue(Some(i.toString))
  implicit def booleanToParam(b: Boolean) = ParamValue(Some(b.toString))
  implicit def intToParam(i: Int) = ParamValue(Some(i.toString))
  implicit def stateToParam[T](i: State[T]) = ParamValue(Some(i.value))
  implicit def externalIdToParam[T](i: ExternalId[T]) = ParamValue(Some(i.id))
  implicit def idToParam[T](i: Id[T]) = ParamValue(Some(i.id.toString))
  implicit def optionToParam[T](i: Option[T])(implicit e: T => ParamValue) = i.map(e) getOrElse ParamValue(None)
  implicit def seqNumToParam[T](seqNum: SequenceNumber[T]) = ParamValue(Some(seqNum.value.toString))
  implicit def modelVersionToParam[M <: StatModel](modelVersion: ModelVersion[M]) = ParamValue(Some(modelVersion.version.toString))
  implicit def emailToParam(emailAddress: EmailAddress) = ParamValue(Some(emailAddress.address))
}

abstract class Method(name: String)
case object GET extends Method("GET")
case object POST extends Method("POST")
case object PUT extends Method("PUT")

/*
  Hello future FortyTwoers. This will help you generate these (still need to handle params, but saves 80% of time):
    val regex = """(POST|GET)\s*(/internal\S*)[\s].*\.(.*)""".r
    def convert(s: String) = s match {
      case regex(a,b,c) => println(s"""def $c = ServiceRoute($a, "$b")""")
    }
    val all = """ ....... """ // (copy from Play routes)
    all.split("\n").filter(_.nonEmpty).map(convert)
 */

object Shoebox extends Service {
  object internal {
    def getNormalizedURI(id: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURI", Param("id", id))
    def getNormalizedURIs(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURIs", Param("ids", ids))
    def getNormalizedURIByURL() = ServiceRoute(POST, "/internal/shoebox/database/getNormalizedURIByURL")
    def getNormalizedUriByUrlOrPrenormalize() = ServiceRoute(POST, "/internal/shoebox/database/getNormalizedUriByUrlOrPrenormalize")
    def internNormalizedURI() = ServiceRoute(POST, "/internal/shoebox/database/internNormalizedURI")
    def getUsers(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getUsers", Param("ids", ids))
    def getUserIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/userIdsByExternalIds", Param("ids", ids))
    def getBasicUsers() = ServiceRoute(POST, "/internal/shoebox/database/getBasicUsers")
    def getBasicUsersNoCache() = ServiceRoute(POST, "/internal/shoebox/database/getBasicUsersNoCache")
    def getEmailAddressesForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getEmailAddressesForUsers")
    def getCollectionIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/collectionIdsByExternalIds", Param("ids", ids))
    def getUserOpt(id: ExternalId[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserOpt", Param("id", id))
    def getUserExperiments(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserExperiments", Param("id", id))
    def getExperimentsByUserIds() = ServiceRoute(POST, "/internal/shoebox/database/getExperimentsByUserIds")
    def getExperimentGenerators() = ServiceRoute(GET, "/internal/shoebox/database/getExperimentGenerators")
    def getConnectedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getConnectedUsers", Param("userId", userId))
    def getBrowsingHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/browsingHistory", Param("userId", userId))
    def getClickHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/clickHistory", Param("userId", userId))
    def getBookmarks(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmark", Param("userId", userId))
    def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksByUriWithoutTitle", Param("uriId", uriId))
    def getLatestKeep() = ServiceRoute(POST, "/internal/shoebox/database/getLatestKeep")
    def saveBookmark() = ServiceRoute(POST, "/internal/shoebox/database/saveBookmark")
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def sendMailToUser() = ServiceRoute(POST, "/internal/shoebox/database/sendMailToUser")
    def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedCollections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getUriIdsInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getUriIdsInCollection", Param("collectionId", collectionId))
    def getCollectionsByUser(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsByUser", Param("userId", userId))
    def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableUris", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getScrapedUris", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getHighestUriSeq() = ServiceRoute(GET, "/internal/shoebox/database/getHighestUriSeq")
    def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getActiveExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getActiveExperiments")
    def getExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getExperiments")
    def getExperiment(id: Id[SearchConfigExperiment]) = ServiceRoute(GET, "/internal/shoebox/database/getExperiment", Param("id", id))
    def saveExperiment = ServiceRoute(POST, "/internal/shoebox/database/saveExperiment")
    def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfoByNetworkAndSocialId", Param("id", id), Param("networkType", networkType))
    def getSocialUserInfosByUserId(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfosByUserId", Param("id", id))
    def getSessionByExternalId(sessionId: ExternalId[UserSession]) = ServiceRoute(GET, "/internal/shoebox/database/sessionByExternalId", Param("sessionId", sessionId))
    def suggestExperts() = ServiceRoute(POST, "/internal/shoebox/learning/suggestExperts")
    def getSearchFriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/searchFriends", Param("userId", userId))
    def getUnfriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/unfriends", Param("userId", userId))
    def logEvent() = ServiceRoute(POST, "/internal/shoebox/logEvent")
    def createDeepLink() = ServiceRoute(POST, "/internal/shoebox/database/createDeepLink")
    def getDeepUrl = ServiceRoute(POST, "/internal/shoebox/database/getDeepUrl")
    def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedUriUpdates", Param("lowSeq", lowSeq.value), Param("highSeq", highSeq.value))
    def kifiHit() = ServiceRoute(POST, "/internal/shoebox/database/kifiHit")
    def assignScrapeTasks(zkId: Long, max: Int) = ServiceRoute(GET, "/internal/shoebox/database/assignScrapeTasks", Param("zkId", zkId), Param("max", max))
    def getScrapeInfo() = ServiceRoute(POST, "/internal/shoebox/database/getScrapeInfo")
    def saveScrapeInfo() = ServiceRoute(POST, "/internal/shoebox/database/saveScrapeInfo")
    def saveNormalizedURI() = ServiceRoute(POST, "/internal/shoebox/database/saveNormalizedURI")
    def savePageInfo() = ServiceRoute(POST, "/internal/shoebox/database/savePageInfo")
    def getImageInfo(id: Id[ImageInfo]) = ServiceRoute(GET, "/internal/shoebox/database/getImageInfo", Param("id", id))
    def saveImageInfo() = ServiceRoute(POST, "/internal/shoebox/database/saveImageInfo")
    def updateNormalizedURI(uriId: Id[NormalizedURI]) = ServiceRoute(POST, "/internal/shoebox/database/updateNormalizedURI", Param("uriId", uriId))
    def recordPermanentRedirect() = ServiceRoute(POST, "/internal/shoebox/database/recordPermanentRedirect")
    def recordScrapedNormalization() = ServiceRoute(POST, "/internal/shoebox/database/recordScrapedNormalization")
    def getProxy(url: String) = ServiceRoute(GET, "/internal/shoebox/database/getProxy", Param("url"))
    def getProxyP() = ServiceRoute(POST, "/internal/shoebox/database/getProxyP")
    def isUnscrapable(url: String, destinationUrl: Option[String]) = ServiceRoute(GET, "/internal/shoebox/database/isUnscrapable", Param("url", url), Param("destinationUrl", destinationUrl))
    def isUnscrapableP() = ServiceRoute(POST, "/internal/shoebox/database/isUnscrapableP")
    //    def scraped() = ServiceRoute(POST, "/internal/shoebox/database/scraped")
    //    def scrapeFailed() = ServiceRoute(POST, "/internal/shoebox/database/scrapeFailed")
    def getFriendRequestBySender(senderId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getFriendRequestBySender", Param("senderId", senderId))
    def getUserValue(userId: Id[User], key: String) = ServiceRoute(GET, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def setUserValue(userId: Id[User], key: String) = ServiceRoute(POST, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def getUserSegment(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/userSegment", Param("userId", userId))
    def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = ServiceRoute(GET, "/internal/shoebox/database/extensionVersion", Param("installationId", installationId))
    def triggerRawKeepImport() = ServiceRoute(POST, "/internal/shoebox/database/triggerRawKeepImport")
    def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = ServiceRoute(POST, "/internal/shoebox/database/triggerSocialGraphFetch", Param("id", socialUserInfoId))
    def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserConnectionsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getSearchFriendsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def isSensitiveURI() = ServiceRoute(POST, "/internal/shoebox/database/isSensitiveURI")
    def updateURIRestriction() = ServiceRoute(POST, "/internal/shoebox/database/updateURIRestriction")
    def sendUnreadMessages() = ServiceRoute(POST, "/internal/shoebox/email/sendUnreadMessages")
    def allURLPatternRules() = ServiceRoute(GET, "/internal/shoebox/database/urlPatternRules")
    def updateScreenshots(id: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/screenshots/update", Param("id", id))
    def getUriImage(id: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/image/getUriImage", Param("id", id))
    def getUriSummary() = ServiceRoute(POST, "/internal/shoebox/image/getUriSummary")
    def getUriSummaries() = ServiceRoute(POST, "/internal/shoebox/image/getUriSummaries")
    def getUserImageUrl(id: Long, width: Int) = ServiceRoute(GET, "/internal/shoebox/image/getUserImageUrl", Param("id", id), Param("width", width))
    def getUnsubscribeUrlForEmail(email: EmailAddress) = ServiceRoute(GET, "/internal/shoebox/email/getUnsubscribeUrlForEmail", Param("email", email))
    def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialConnections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialUserInfos", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getEmailAccountUpdates", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
  }
}

object Search extends Service {
  object internal {
    def updateBrowsingHistory(id: Id[User]) = ServiceRoute(POST, s"/internal/search/events/browsed/${id.id}")
    def warmUpUser(id: Id[User]) = ServiceRoute(GET, s"/internal/search/warmUp/${id.id}")
    def sharingUserInfo(id: Id[User]) = ServiceRoute(POST, s"/internal/search/search/sharingUserInfo/${id.id}")
    def updateURIGraph() = ServiceRoute(POST, "/internal/search/uriGraph/update")
    def uriGraphReindex() = ServiceRoute(POST, "/internal/search/uriGraph/reindex")
    def uriGraphDumpLuceneDocument(id: Id[User]) = ServiceRoute(POST, s"/internal/search/uriGraph/dumpDoc/${id.id}")
    def collectionReindex() = ServiceRoute(POST, "/internal/search/collection/reindex")
    def collectionDumpLuceneDocument(id: Id[Collection], userId: Id[User]) = ServiceRoute(POST, "/internal/search/collection/dumpDoc", Param("id", id), Param("userId", userId))
    def searchUpdate() = ServiceRoute(POST, "/internal/search/index/update")
    def searchReindex() = ServiceRoute(POST, "/internal/search/index/reindex")
    def getSequenceNumber() = ServiceRoute(GET, "/internal/search/index/sequenceNumber")
    def userReindex() = ServiceRoute(POST, "/internal/search/user/reindex")
    def refreshSearcher() = ServiceRoute(POST, "/internal/search/index/refreshSearcher")
    def refreshPhrases() = ServiceRoute(POST, "/internal/search/index/refreshPhrases")
    def searchDumpLuceneDocument(id: Id[NormalizedURI]) = ServiceRoute(POST, s"/internal/search/index/dumpDoc/${id.id}")
    def searchKeeps(userId: Id[User], query: String) = ServiceRoute(POST, "/internal/search/search/keeps", Param("userId", userId), Param("query", query))
    def searchUsers() = ServiceRoute(POST, "/internal/search/search/users")
    def userTypeahead() = ServiceRoute(POST, "/internal/search/search/userTypeahead")
    def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String) =
      ServiceRoute(GET, "/internal/search/search/explainResult", Param("query", query), Param("userId", userId), Param("uriId", uriId), Param("lang", lang))
    def correctSpelling(input: String, enableBoost: Boolean) = ServiceRoute(GET, "/internal/search/spell/suggest", Param("input", input), Param("enableBoost", enableBoost))
    def showUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}")
    def setUserConfig(id: Id[User]) = ServiceRoute(POST, s"/internal/search/searchConfig/${id.id}/set")
    def resetUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}/reset")
    def getSearchDefaultConfig = ServiceRoute(GET, "/internal/search/defaultSearchConfig/defaultSearchConfig")
    def friendMapJson(userId: Id[User], query: Option[String] = None, minKeeps: Option[Int] = None) = ServiceRoute(GET, "/internal/search/search/friendMapJson", Param("userId", userId), Param("query", query), Param("minKeeps", minKeeps))

    def searchWithConfig() = ServiceRoute(POST, "/internal/searchWithConfig")

    def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/leaveOneOut", Param("queryText", queryText), Param("stem", stem), Param("useSketch", useSketch))
    def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/allSubsets", Param("queryText", queryText), Param("stem", stem), Param("useSketch", useSketch))
    def semanticSimilarity(query1: String, query2: String, stem: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/similarity", Param("query1", query1), Param("query2", query2), Param("stem", stem))
    def visualizeSemanticVector() = ServiceRoute(POST, "/internal/search/semanticVector/visualize")
    def semanticLoss(queryText: String) = ServiceRoute(GET, "/internal/search/semanticVector/semanticLoss", Param("queryText", queryText))
    def indexInfoList() = ServiceRoute(GET, "/internal/search/indexInfo/listAll")
    def updateUserGraph() = ServiceRoute(POST, "/internal/search/userGraph/update")
    def updateSearchFriendGraph() = ServiceRoute(POST, "/internal/search/searchFriendGraph/update")
    def reindexUserGraphs() = ServiceRoute(POST, "/internal/search/userGraphs/reindex")
    def updateUserIndex() = ServiceRoute(POST, "/internal/search/user/update")
    def getFeeds(userId: Id[User], limit: Int) = ServiceRoute(GET, "/internal/search/feed", Param("userId", userId), Param("limit", limit))
    def searchMessages(userId: Id[User], query: String, page: Int = 0) = ServiceRoute(GET, "/internal/search/searchMessages", Param("userId", userId), Param("query", query), Param("page", page))

    def distSearch() = ServiceRoute(POST, "/internal/search/dist/search")
    def distLangFreqs() = ServiceRoute(POST, "/internal/search/dist/langFreqs")
    def distFeeds() = ServiceRoute(POST, "/internal/search/dist/feeds")
  }
}

object Eliza extends Service {
  object internal {
    def sendToUserNoBroadcast() = ServiceRoute(POST, "/internal/eliza/sendToUserNoBroadcast")
    def sendToUser() = ServiceRoute(POST, "/internal/eliza/sendToUser")
    def sendToAllUsers() = ServiceRoute(POST, "/internal/eliza/sendToAllUsers")
    def connectedClientCount() = ServiceRoute(GET, "/internal/eliza/connectedClientCount")
    def sendGlobalNotification() = ServiceRoute(POST, "/internal/eliza/sendGlobalNotification")
    def unsendNotification(messageHandle: Id[MessageHandle]) = ServiceRoute(GET, "/internal/eliza/unsendNotification", Param("id", messageHandle))
    def importThread() = ServiceRoute(POST, "/internal/eliza/importThread")
    def getUserThreadStats(userId: Id[User]) = ServiceRoute(GET, "/internal/eliza/getUserThreadStats", Param("userId", userId))
    def getNonUserThreadMuteInfo(publicId: String) = ServiceRoute(GET, "/internal/eliza/getNonUserThreadMuteInfo", Param("publicId", publicId))
    def setNonUserThreadMuteState(publicId: String, muted: Boolean) = ServiceRoute(POST, "/internal/eliza/setNonUserThreadMuteState", Param("publicId", publicId), Param("muted", muted))
    def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long) = ServiceRoute(GET, "/internal/eliza/getThreadContentForIndexing", Param("sequenceNumber", sequenceNumber), Param("maxBatchSize", maxBatchSize))
    def getRenormalizationSequenceNumber() = ServiceRoute(GET, "/internal/eliza/sequenceNumber/renormalization")
    def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/eliza/keepAttribution", Param("userId", userId), Param("uriId", uriId))
  }
}

object Heimdal extends Service {
  object internal {
    def getMetricData(repo: String, name: String) = ServiceRoute(GET, s"/internal/heimdal/$repo/getMetricData", Param("name", name))
    def updateMetrics() = ServiceRoute(GET, "/internal/heimdal/updateMetrics")
    def getRawEvents(repo: String, eventTypes: Seq[String], limit: Int, window: Int) = ServiceRoute(GET, s"/internal/heimdal/$repo/rawEvents", Param("events", eventTypes.mkString(",")), Param("limit", limit), Param("window", window))
    def getEventDescriptors(repo: String) = ServiceRoute(GET, s"/internal/heimdal/$repo/eventDescriptors")
    def updateEventDescriptor(repo: String) = ServiceRoute(POST, s"/internal/heimdal/$repo/eventDescriptors")
    def deleteUser(userId: Id[User]) = ServiceRoute(GET, s"/internal/heimdal/user/delete", Param("userId", userId))
    def incrementUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/increment", Param("userId", userId))
    def setUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/set", Param("userId", userId))
    def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = ServiceRoute(GET, "/internal/heimdal/user/alias", Param("userId", userId), Param("externalId", externalId))
    def getLastDelightedAnswerDate(userId: Id[User]) = ServiceRoute(GET, s"/internal/heimdal/user/delighted/time", Param("userId", userId))
    def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String) = ServiceRoute(POST, s"/internal/heimdal/user/delighted/answer", Param("userId", userId), Param("externalId", externalId), Param("email", email), Param("name", name))
    def cancelDelightedSurvey(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String) = ServiceRoute(POST, s"/internal/heimdal/user/delighted/cancel", Param("userId", userId), Param("externalId", externalId), Param("email", email), Param("name", name))
  }
}

object ABook extends Service {
  object internal {
    def importContacts(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/importContacts")
    def uploadContacts(userId: Id[User], origin: ABookOriginType) = ServiceRoute(POST, s"/internal/abook/${origin.name}/uploadContacts?userId=${userId.id}")
    def formUpload(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/formUpload")
    def getABookInfos(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfos")
    def getAllABookInfos() = ServiceRoute(GET, s"/internal/abooks")
    def getPagedABookInfos(page: Int, size: Int) = ServiceRoute(GET, s"/internal/abooks/page/${page}?size=${size}")
    def getABooksCount() = ServiceRoute(GET, s"/internal/abooksCount/")
    def getABookInfo(userId: Id[User], id: Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfo", Param("userId", userId), Param("id", id))
    def getABookInfoByExternalId(id: ExternalId[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/getABookInfoByExternalId", Param("externalId", id))
    def getEContactCount(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContactCount")
    def getABookRawInfos(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookRawInfos")
    def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getOAuth2Token", Param("abookId", abookId))
    def refreshPrefixFilter(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/refreshPrefixFilter")
    def refreshPrefixFiltersByIds() = ServiceRoute(POST, s"/internal/abook/refreshPrefixFiltersByIds")
    def refreshAllPrefixFilters() = ServiceRoute(GET, s"/internal/abook/refreshAllPrefixFilters")
    def richConnectionUpdate() = ServiceRoute(POST, s"/internal/abook/richConnectionUpdate")
    def blockRichConnection() = ServiceRoute(POST, s"/internal/abook/blockRichConnection")
    def ripestFruit(userId: Id[User], howMany: Int) = ServiceRoute(GET, s"/internal/abook/ripestFruit?userId=${userId.id}&howMany=$howMany")
    def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]) = ServiceRoute(GET, s"/internal/abook/${userId}/countInvitationsSent", friend match {
      case Left(friendSocialId) => Param("friendSocialId", friendSocialId)
      case Right(friendEmailAddress) => Param("friendEmailAddress", friendEmailAddress)
    })
    def getRipestFruits(userId: Id[User], page: Int, pageSize: Int) = ServiceRoute(GET, s"/internal/abook/$userId/ripestFruits", Param("page", page), Param("pageSize", pageSize))
    def validateAllContacts(readOnly: Boolean) = ServiceRoute(GET, s"/internal/abook/validateAllContacts", Param("readOnly", readOnly))
    def hideEmailFromUser(userId: Id[User], email: EmailAddress) = ServiceRoute(POST, s"/internal/abook/${userId.id}/hideEmailFromUser", Param("email", email))
    def getContactNameByEmail(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/getContactNameByEmail")
    def internKifiContact(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/internKifiContact")
    def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/prefixQuery", Param("q", query), Param("maxHits", maxHits))
    def getContactsByUser(userId: Id[User], page: Int, pageSize: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/getContacts", Param("page", page), Param("pageSize", pageSize))
  }
}

object Scraper extends Service {
  object internal {
    def getBasicArticle() = ServiceRoute(POST, s"/internal/scraper/getBasicArticle")
    def getSignature() = ServiceRoute(POST, s"/internal/scraper/getSignature")
    def getPornDetectorModel() = ServiceRoute(GET, s"/internal/scraper/getPornDetectorModel")
    def detectPorn() = ServiceRoute(POST, s"/internal/scraper/pornDetector/detectPorn")
    def whitelist() = ServiceRoute(POST, s"/internal/scraper/pornDetector/whitelist")
    def getEmbedlyImageInfos() = ServiceRoute(POST, s"/internal/scraper/embedly/imageInfos")
    def getEmbedlyInfo() = ServiceRoute(POST, s"/internal/scraper/embedly/embedlyInfo")
    def getURISummaryFromEmbedly() = ServiceRoute(POST, s"/internal/scraper/uriSummary/embedly")
    def getURIWordCount() = ServiceRoute(POST, s"/internal/scraper/uriWordCount")
  }
}

object Cortex extends Service {
  object internal {
    def word2vecSimilairty(word1: String, word2: String) = ServiceRoute(GET, "/internal/cortex/word2vec/wordSimilarity", Param("word1", word1), Param("word2", word2))
    def keywordsAndBow() = ServiceRoute(POST, "/internal/cortex/word2vec/keywordsAndBow")
    def uriKeywords(uri: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/cortex/word2vec/uriKeywords", Param("uri", uri))
    def batchGetURIKeywords() = ServiceRoute(POST, "/internal/cortex/word2vec/batchUriKeywords")
    def word2vecURISimilarity(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]) = ServiceRoute(GET, s"/internal/cortex/word2vec/uriSimilarity", Param("uri1", uri1), Param("uri2", uri2))
    def word2vecUserSimilarity() = ServiceRoute(POST, "/internal/cortex/word2vec/userSimilarity")
    def word2vecQueryUriSimilarity() = ServiceRoute(POST, "/internal/cortex/word2vec/queryUriSimilarity")
    def word2vecUserUriSimilarity() = ServiceRoute(POST, "/internal/cortex/word2vec/userUriSimilarity")
    def word2vecFeedUserUris() = ServiceRoute(POST, "/internal/cortex/word2vec/feedUserUris")

    def ldaNumOfTopics = ServiceRoute(GET, "/internal/cortex/lda/numOfTopics")
    def ldaShowTopics(fromId: Int, toId: Int, topN: Int) = ServiceRoute(GET, "/internal/cortex/lda/showTopics", Param("fromId", fromId), Param("toId", toId), Param("topN", topN))
    def ldaWordTopic(word: String) = ServiceRoute(GET, "/internal/cortex/lda/wordTopic", Param("word", word))
    def ldaDocTopic() = ServiceRoute(POST, "/internal/cortex/lda/docTopic")
    def saveEdits() = ServiceRoute(POST, "/internal/cortex/lda/saveEdits")
    def getLDAFeatures() = ServiceRoute(POST, "/internal/cortex/lda/ldaFeatures")

    def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/cortex/data/sparseLDAFeaturesChanged", Param("modelVersion", modelVersion), Param("seqNum", seqNum), Param("fetchSize", fetchSize))
  }
}

object Graph extends Service {
  object internal {
    def getGraphStatistics() = ServiceRoute(GET, "/internal/graph/statistics")
    def getGraphUpdaterState() = ServiceRoute(GET, "/internal/graph/state")
    def getGraphKinds() = ServiceRoute(GET, "/internal/graph/kinds")
    def wander() = ServiceRoute(POST, "/internal/graph/wander")
  }
}

object Maven extends Service {
  object internal {
  }
}

object Common {
  object internal {
    def benchmarksResults() = ServiceRoute(GET, "/internal/benchmark")
    def version() = ServiceRoute(GET, "/internal/version")
    def threadDetails(name: Option[String], state: Option[String]) = ServiceRoute(GET, "/internal/common/threadDetails", Param("name", name), Param("state", state), Param("hideStack"))
    def removeAllFromLocalCache(prefix: Option[String]) = ServiceRoute(GET, "/internal/cache/removeAll", Param("prefix", prefix))
  }
}

