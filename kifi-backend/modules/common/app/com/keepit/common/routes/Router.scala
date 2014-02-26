package com.keepit.common.routes

import com.keepit.common.db.ExternalId
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.db.State
import com.keepit.search.SearchConfigExperiment
import java.net.URLEncoder
import com.keepit.common.strings.UTF8

trait Service

case class ServiceRoute(method: Method, path: String, params: Param*) {
  def url = path + (if(params.nonEmpty) "?" + params.map({ p =>
    URLEncoder.encode(p.key, UTF8) + (if(p.value.value != "") "=" + URLEncoder.encode(p.value.value, UTF8) else "")
  }).mkString("&") else "")
}

case class Param(key: String, value: ParamValue = ParamValue("")) {
  override def toString(): String = s"key->${value.value}"
}

case class ParamValue(value: String)

object ParamValue {
  implicit def stringToParam(i: String) = ParamValue(i)
  implicit def longToParam(i: Long) = ParamValue(i.toString)
  implicit def booleanToParam(b: Boolean) = ParamValue(b.toString)
  implicit def intToParam(i: Int) = ParamValue(i.toString)
  implicit def stateToParam[T](i: State[T]) = ParamValue(i.value)
  implicit def externalIdToParam[T](i: ExternalId[T]) = ParamValue(i.id)
  implicit def idToParam[T](i: Id[T]) = ParamValue(i.id.toString)
  implicit def optionToParam[T](i: Option[T])(implicit e: T => ParamValue) = if(i.nonEmpty) e(i.get) else ParamValue("")
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
    def getNormalizedURI(id: Long) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURI", Param("id", id))
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
    def getBookmarksChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksByUriWithoutTitle", Param("uriId", uriId))
    def getLatestBookmark(uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getLatestBookmark", Param("uriId", uriId))
    def saveBookmark() = ServiceRoute(POST, "/internal/shoebox/database/saveBookmark")
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def sendMailToUser() = ServiceRoute(POST, "/internal/shoebox/database/sendMailToUser")
    def getPhrasesChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getCollectionsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedCollections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarksInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksInCollection", Param("collectionId", collectionId))
    def getUriIdsInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getUriIdsInCollection", Param("collectionId", collectionId))
    def getCollectionsByUser(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsByUser", Param("userId", userId))
    def getIndexable(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableUris(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableUris", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getScrapedUris(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getScrapedUris", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getHighestUriSeq() = ServiceRoute(GET, "/internal/shoebox/database/getHighestUriSeq")
    def getUserIndexable(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getActiveExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getActiveExperiments")
    def getExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getExperiments")
    def getExperiment(id: Id[SearchConfigExperiment]) = ServiceRoute(GET, "/internal/shoebox/database/getExperiment", Param("id", id))
    def saveExperiment = ServiceRoute(POST, "/internal/shoebox/database/saveExperiment")
    def getSocialUserInfoByNetworkAndSocialId(id: String, networkType: String) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfoByNetworkAndSocialId", Param("id", id), Param("networkType", networkType))
    def getSocialUserInfosByUserId(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfosByUserId", Param("id", id))
    def getSessionByExternalId(sessionId: ExternalId[UserSession]) = ServiceRoute(GET, "/internal/shoebox/database/sessionByExternalId", Param("sessionId", sessionId))
    def userChannelFanout() = ServiceRoute(POST, "/internal/shoebox/channel/user")
    def userChannelBroadcastFanout() = ServiceRoute(POST, "/internal/shoebox/channel/userBroadcast")
    def userChannelCountFanout() = ServiceRoute(POST, "/internal/shoebox/channel/userCount")
    def uriChannelFanout() = ServiceRoute(POST, "/internal/shoebox/channel/uri")
    def uriChannelCountFanout() = ServiceRoute(POST, "/internal/shoebox/channel/uriCount")
    def suggestExperts() = ServiceRoute(POST, "/internal/shoebox/learning/suggestExperts")
    def getSearchFriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/searchFriends", Param("userId", userId))
    def getUnfriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/unfriends", Param("userId", userId))
    def logEvent() = ServiceRoute(POST, "/internal/shoebox/logEvent")
    def createDeepLink() = ServiceRoute(POST, "/internal/shoebox/database/createDeepLink")
    def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long) =  ServiceRoute(GET, "/internal/shoebox/database/getNormalizedUriUpdates", Param("lowSeq", lowSeq), Param("highSeq", highSeq))
    def clickAttribution() = ServiceRoute(POST, "/internal/shoebox/database/clickAttribution")
    def assignScrapeTasks(zkId:Long, max:Int) = ServiceRoute(GET, "/internal/shoebox/database/assignScrapeTasks", Param("zkId", zkId), Param("max", max))
    def getScrapeInfo() = ServiceRoute(POST, "/internal/shoebox/database/getScrapeInfo")
    def saveScrapeInfo()  = ServiceRoute(POST, "/internal/shoebox/database/saveScrapeInfo")
    def saveNormalizedURI() = ServiceRoute(POST, "/internal/shoebox/database/saveNormalizedURI")
    def updateNormalizedURI(uriId: Id[NormalizedURI]) = ServiceRoute(POST, "/internal/shoebox/database/updateNormalizedURI", Param("uriId", uriId))
    def recordPermanentRedirect() = ServiceRoute(POST, "/internal/shoebox/database/recordPermanentRedirect")
    def recordScrapedNormalization() = ServiceRoute(POST, "/internal/shoebox/database/recordScrapedNormalization")
    def getProxy(url: String) = ServiceRoute(GET, "/internal/shoebox/database/getProxy", Param("url"))
    def getProxyP() = ServiceRoute(POST, "/internal/shoebox/database/getProxyP")
    def isUnscrapable(url: String, destinationUrl: Option[String]) = ServiceRoute(GET, "/internal/shoebox/database/isUnscrapable", Param("url", url), Param("destinationUrl", destinationUrl))
    def isUnscrapableP() = ServiceRoute(POST, "/internal/shoebox/database/isUnscrapableP")
    def scraped() = ServiceRoute(POST, "/internal/shoebox/database/scraped")
    def scrapeFailed() = ServiceRoute(POST, "/internal/shoebox/database/scrapeFailed")
    def getFriendRequestBySender(senderId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getFriendRequestBySender", Param("senderId", senderId) )
    def getUserValue(userId: Id[User], key: String) = ServiceRoute(GET, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def setUserValue(userId: Id[User], key: String) = ServiceRoute(POST, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def getUserSegment(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/userSegment", Param("userId", userId))
    def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = ServiceRoute(GET, "/internal/shoebox/database/extensionVersion", Param("installationId", installationId))
    def triggerRawKeepImport() = ServiceRoute(POST, "/internal/shoebox/database/triggerRawKeepImport")
    def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = ServiceRoute(POST, "/internal/shoebox/database/triggerSocialGraphFetch", Param("id", socialUserInfoId))
    def getUserConnectionsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserConnectionsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getSearchFriendsChanged(seqNum: Long, fetchSize: Int)  = ServiceRoute(GET, "/internal/shoebox/database/getSearchFriendsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def isSensitiveURI() = ServiceRoute(POST, "/internal/shoebox/database/isSensitiveURI")
  }
}

object Search extends Service {
  object internal {
    def updateBrowsingHistory(id: Id[User]) = ServiceRoute(POST, s"/internal/search/events/browsed/${id.id}")
    def warmUpUser(id: Id[User]) = ServiceRoute(GET, s"/internal/search/warmUp/${id.id}")
    def uriGraphInfo() = ServiceRoute(GET, "/internal/search/uriGraph/info")
    def sharingUserInfo(id: Id[User]) = ServiceRoute(POST, s"/internal/search/uriGraph/sharingUserInfo/${id.id}")
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
    def updateUserGraph()= ServiceRoute(POST, "/internal/search/userGraph/update")
    def updateSearchFriendGraph() = ServiceRoute(POST, "/internal/search/searchFriendGraph/update")
    def reindexUserGraphs() = ServiceRoute(POST, "/internal/search/userGraphs/reindex")
    def getFeeds(userId: Id[User], limit: Int) = ServiceRoute(GET, "/internal/search/feed", Param("userId", userId), Param("limit", limit))
  }
}

object Eliza extends Service {
  object internal {
    def sendToUserNoBroadcast() = ServiceRoute(POST, "/internal/eliza/sendToUserNoBroadcast")
    def sendToUser() = ServiceRoute(POST, "/internal/eliza/sendToUser")
    def sendToAllUsers() = ServiceRoute(POST, "/internal/eliza/sendToAllUsers")
    def connectedClientCount() = ServiceRoute(GET, "/internal/eliza/connectedClientCount")
    def sendGlobalNotification() = ServiceRoute(POST, "/internal/eliza/sendGlobalNotification")
    def importThread() = ServiceRoute(POST, "/internal/eliza/importThread")
    def getUserThreadStats(userId: Id[User]) = ServiceRoute(GET, "/internal/eliza/getUserThreadStats", Param("userId", userId))
    def getThreadContentForIndexing(sequenceNumber: Long, maxBatchSize: Long) = ServiceRoute(GET, "/internal/eliza/getThreadContentForIndexing", Param("sequenceNumber", sequenceNumber), Param("maxBatchSize", maxBatchSize))
  }
}

object Heimdal extends Service {
  object internal {
    def trackEvent() = ServiceRoute(POST, "/internal/heimdal/trackEvent")
    def trackEvents() = ServiceRoute(POST, "/internal/heimdal/trackEvents")
    def getMetricData(repo: String, name: String) = ServiceRoute(GET, s"/internal/heimdal/$repo/getMetricData", Param("name", name))
    def updateMetrics() = ServiceRoute(GET, "/internal/heimdal/updateMetrics")
    def getRawEvents(repo: String, eventTypes: Seq[String], limit: Int, window: Int) = ServiceRoute(GET, s"/internal/heimdal/$repo/rawEvents", Param("events", eventTypes.mkString(",")), Param("limit", limit), Param("window", window))
    def getEventDescriptors(repo: String) = ServiceRoute(GET, s"/internal/heimdal/$repo/eventDescriptors")
    def updateEventDescriptor(repo: String) = ServiceRoute(POST, s"/internal/heimdal/$repo/eventDescriptors")
    def deleteUser(userId: Id[User]) = ServiceRoute(GET, s"/internal/heimdal/user/delete", Param("userId", userId))
    def incrementUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/increment", Param("userId", userId))
    def setUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/set", Param("userId", userId))
    def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = ServiceRoute(GET, "/internal/heimdal/user/alias", Param("userId", userId), Param("externalId", externalId))
  }
}

object ABook extends Service {
  object internal {
    def importContacts(userId:Id[User])  = ServiceRoute(POST, s"/internal/abook/${userId.id}/importContacts")
    def uploadContacts(userId:Id[User], origin:ABookOriginType) = ServiceRoute(POST, s"/internal/abook/${origin.name}/uploadContacts?userId=${userId.id}")
    def formUpload(userId:Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/formUpload")
    def getABookInfos(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfos")
    def getAllABookInfos() = ServiceRoute(GET, s"/internal/abooks")
    def getPagedABookInfos(page:Int, size:Int) = ServiceRoute(GET, s"/internal/abooks/page/${page}?size=${size}")
    def getABooksCount() = ServiceRoute(GET, s"/internal/abooksCount/")
    def getABookInfo(userId:Id[User], id:Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfo", Param("userId", userId), Param("id", id))
    def getContacts(userId:Id[User], maxRows:Int) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getContacts", Param("maxRows", maxRows))
    def getEContacts(userId:Id[User], maxRows:Int) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContacts", Param("maxRows", maxRows))
    def getEContactCount(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContactCount")
    def getEContactById(contactId:Id[EContact]) = ServiceRoute(GET, s"/internal/abook/getEContactById", Param("contactId", contactId))
    def getEContactsByIds() = ServiceRoute(POST, s"/internal/abook/getEContactsByIds")
    def getEContactByEmail(userId:Id[User], email:String) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContactByEmail", Param("email", email))
    def getABookRawInfos(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookRawInfos")
    def getOAuth2Token(userId:Id[User], abookId:Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getOAuth2Token", Param("abookId", abookId))
    def getOrCreateEContact(userId:Id[User], email:String, name:Option[String], firstName:Option[String], lastName:Option[String]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getOrCreateEContact", Param("email", email), Param("name", name), Param("firstName", firstName), Param("lastName", lastName))
    def queryEContacts(userId:Id[User], limit:Int, search:Option[String], after:Option[String]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/queryEContacts", Param("limit", limit), Param("search", search), Param("after", after))
    def prefixSearch(userId:Id[User], query:String) = ServiceRoute(GET, s"/internal/abook/${userId.id}/prefixSearch", Param("query", query))
    def prefixQuery(userId:Id[User], limit:Int, search:Option[String], after:Option[String]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/prefixQuery", Param("limit", limit), Param("search", search), Param("after", after))
    def refreshPrefixFilter(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/refreshPrefixFilter")
    def refreshPrefixFiltersByIds() = ServiceRoute(POST, s"/internal/abook/refreshPrefixFiltersByIds")
    def refreshAllPrefixFilters() = ServiceRoute(GET, s"/internal/abook/refreshAllPrefixFilters")
    def richConnectionUpdate() = ServiceRoute(POST, s"/internal/abook/richConnectionUpdate")
  }
}

object Scraper extends Service {
  object internal {
    def asyncScrapeArticle() = ServiceRoute(POST, s"/internal/scraper/asyncScrape")
    def asyncScrapeArticleWithInfo() = ServiceRoute(POST, s"/internal/scraper/asyncScrapeWithInfo")
    def asyncScrapeArticleWithRequest() = ServiceRoute(POST, s"/internal/scraper/asyncScrapeWithRequest")
    def scheduleScrape() = ServiceRoute(POST, s"/internal/scraper/scheduleScrape")
    def scheduleScrapeWithRequest() = ServiceRoute(POST, s"/internal/scraper/scheduleScrapeWithRequest")
    def getBasicArticle() = ServiceRoute(POST, s"/internal/scraper/getBasicArticle")
    def getSignature() = ServiceRoute(POST, s"/internal/scraper/getSignature")
    def getPornDetectorModel() = ServiceRoute(GET, s"/internal/scraper/getPornDetectorModel")
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

