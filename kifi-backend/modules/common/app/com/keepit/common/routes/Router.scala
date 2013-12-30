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
    def getEmailAddressesForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getEmailAddressesForUsers")
    def getCollectionIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/collectionIdsByExternalIds", Param("ids", ids))
    def getUserOpt(id: ExternalId[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserOpt", Param("id", id))
    def getUserExperiments(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserExperiments", Param("id", id))
    def getExperimentsByUserIds() = ServiceRoute(POST, "/internal/shoebox/database/getExperimentsByUserIds")
    def getConnectedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getConnectedUsers", Param("userId", userId))
    def getBrowsingHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/browsingHistory", Param("userId", userId))
    def getClickHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/clickHistory", Param("userId", userId))
    def getBookmarks(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmark", Param("userId", userId))
    def getBookmarksChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksByUriWithoutTitle", Param("uriId", uriId))
    def getLatestBookmark(uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getLatestBookmark", Param("uriId", uriId))
    def saveBookmark() = ServiceRoute(POST, "/internal/shoebox/database/saveBookmark")
    def getCommentsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedComment", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def sendMailToUser() = ServiceRoute(POST, "/internal/shoebox/database/sendMailToUser")
    def getPhrasesChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getCollectionsChanged(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedCollections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarksInCollection(collectionId: Id[Collection]) = ServiceRoute(GET, "/internal/shoebox/database/getBookmarksInCollection", Param("collectionId", collectionId))
    def getCollectionsByUser(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getCollectionsByUser", Param("userId", userId))
    def getIndexable(seqNum: Long, fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
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
    def logEvent() = ServiceRoute(POST, "/internal/shoebox/logEvent")
    def createDeepLink() = ServiceRoute(POST, "/internal/shoebox/database/createDeepLink")
    def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long) =  ServiceRoute(GET, "/internal/shoebox/database/getNormalizedUriUpdates", Param("lowSeq", lowSeq), Param("highSeq", highSeq))
    def clickAttribution() = ServiceRoute(POST, "/internal/shoebox/database/clickAttribution")
    def getScrapeInfo() = ServiceRoute(POST, "/internal/shoebox/database/getScrapeInfo")
    def saveScrapeInfo()  = ServiceRoute(POST, "/internal/shoebox/database/saveScrapeInfo")
    def saveNormalizedURI() = ServiceRoute(POST, "/internal/shoebox/database/saveNormalizedURI")
    def recordPermanentRedirect() = ServiceRoute(POST, "/internal/shoebox/database/recordPermanentRedirect")
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
  }
}

object Search extends Service {
  object internal {
    def logResultClicked() = ServiceRoute(POST, "/internal/search/events/resultClicked")
    def logSearchEnded() = ServiceRoute(POST, "/internal/search/events/searchEnded")
    def updateBrowsingHistory(id: Id[User]) = ServiceRoute(POST, s"/internal/search/events/browsed/${id.id}")
    def warmUpUser(id: Id[User]) = ServiceRoute(GET, s"/internal/search/warmUp/${id.id}")
    def commentIndexInfo() = ServiceRoute(GET, "/internal/search/comment/info")
    def commentReindex() = ServiceRoute(GET, "/internal/search/comment/reindex")
    def uriGraphInfo() = ServiceRoute(GET, "/internal/search/uriGraph/info")
    def sharingUserInfo(id: Id[User]) = ServiceRoute(POST, s"/internal/search/uriGraph/sharingUserInfo/${id.id}")
    def updateURIGraph() = ServiceRoute(POST, "/internal/search/uriGraph/update")
    def uriGraphReindex() = ServiceRoute(POST, "/internal/search/uriGraph/reindex")
    def uriGraphDumpLuceneDocument(id: Id[User]) = ServiceRoute(POST, s"/internal/search/uriGraph/dumpDoc/${id.id}")
    def collectionReindex() = ServiceRoute(POST, "/internal/search/collection/reindex")
    def collectionDumpLuceneDocument(id: Id[Collection], userId: Id[User]) = ServiceRoute(POST, "/internal/search/collection/dumpDoc", Param("id", id), Param("userId", userId))
    def indexInfo() = ServiceRoute(GET, "/internal/search/index/info")
    def searchUpdate() = ServiceRoute(POST, "/internal/search/index/update")
    def searchReindex() = ServiceRoute(POST, "/internal/search/index/reindex")
    def getSequenceNumber() = ServiceRoute(GET, "/internal/search/index/sequenceNumber")
    def refreshSearcher() = ServiceRoute(POST, "/internal/search/index/refreshSearcher")
    def refreshPhrases() = ServiceRoute(POST, "/internal/search/index/refreshPhrases")
    def searchDumpLuceneDocument(id: Id[NormalizedURI]) = ServiceRoute(POST, s"/internal/search/index/dumpDoc/${id.id}")
    def searchKeeps(userId: Id[User], query: String) = ServiceRoute(POST, "/internal/search/search/keeps", Param("userId", userId), Param("query", query))
    def searchUsers() = ServiceRoute(POST, "/internal/search/search/users")
    def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String) =
      ServiceRoute(GET, "/internal/search/search/explainResult", Param("query", query), Param("userId", userId), Param("uriId", uriId), Param("lang", lang))
    def causeError() = ServiceRoute(GET, "/internal/search/search/causeError")
    def causeHandbrakeError() = ServiceRoute(GET, "/internal/search/search/causeHandbrakeError")
    def correctSpelling(input: String, enableBoost: Boolean) = ServiceRoute(GET, "/internal/search/spell/suggest", Param("input", input), Param("enableBoost", enableBoost))
    def showUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}")
    def setUserConfig(id: Id[User]) = ServiceRoute(POST, s"/internal/search/searchConfig/${id.id}/set")
    def resetUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}/reset")
    def getSearchDefaultConfig = ServiceRoute(GET, "/internal/search/defaultSearchConfig/defaultSearchConfig")
    def friendMapJson(userId: Id[User], query: Option[String] = None, minKeeps: Option[Int] = None) = ServiceRoute(GET, "/internal/search/search/friendMapJson", Param("userId", userId), Param("query", query), Param("minKeeps", minKeeps))
    def search(
        userId: Id[User],
        noSearchExperiments: Boolean,
        acceptLangs: Seq[String],
        rawQuery: String) = {
        val params = "userId=" + userId.id.toString +
                     "&nse=" + noSearchExperiments +
                     "&al=" + acceptLangs.mkString(",") +
                     "&" + rawQuery
        ServiceRoute(GET, "/internal/search?" + params)
    }

    def searchWithConfig() = ServiceRoute(POST, "/internal/searchWithConfig")

    def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/leaveOneOut", Param("queryText", queryText), Param("stem", stem), Param("useSketch", useSketch))
    def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/allSubsets", Param("queryText", queryText), Param("stem", stem), Param("useSketch", useSketch))
    def semanticSimilarity(query1: String, query2: String, stem: Boolean) = ServiceRoute(GET, "/internal/search/semanticVector/similarity", Param("query1", query1), Param("query2", query2), Param("stem", stem))
    def visualizeSemanticVector() = ServiceRoute(POST, "/internal/search/semanticVector/visualize")
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
    def importContactsP(userId:Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/importContactsP")
    def importContacts(userId:Id[User], provider:String, accessToken:String) = ServiceRoute(GET, s"/internal/abook/${userId.id}/importContacts", Param("provider", provider), Param("accessToken", accessToken))
    def uploadForUser(userId:Id[User], origin:ABookOriginType) = ServiceRoute(POST, s"/internal/abook/${origin.name}/uploadForUser?userId=${userId.id}")
    def upload(userId:Id[User], origin:ABookOriginType) = ServiceRoute(POST, s"/internal/abook/${userId.id}/${origin.name}/upload")
    def uploadDirect(userId:Id[User], origin:ABookOriginType) = ServiceRoute(POST, s"/internal/abook/${userId.id}/${origin.name}/uploadDirect")
    def getABookInfos(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfos")
    def getABookInfo(userId:Id[User], id:Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookInfo", Param("userId", userId), Param("id", id))
    def getContacts(userId:Id[User], maxRows:Int) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getContacts", Param("maxRows", maxRows))
    def getEContacts(userId:Id[User], maxRows:Int) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContacts", Param("maxRows", maxRows))
    def getEContactCount(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContactCount")
    def getEContactById(contactId:Id[EContact]) = ServiceRoute(GET, s"/internal/abook/getEContactById", Param("contactId", contactId))
    def getEContactByEmail(userId:Id[User], email:String) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getEContactByEmail", Param("email", email))
    def getABookRawInfos(userId:Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getABookRawInfos")
    def getOAuth2Token(userId:Id[User], abookId:Id[ABookInfo]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getOAuth2Token", Param("abookId", abookId))
    def getOrCreateEContact(userId:Id[User], email:String, name:Option[String], firstName:Option[String], lastName:Option[String]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/getOrCreateEContact", Param("email", email), Param("name", name), Param("firstName", firstName), Param("lastName", lastName))
    def queryEContacts(userId:Id[User], limit:Int, search:Option[String], after:Option[String]) = ServiceRoute(GET, s"/internal/abook/${userId.id}/queryEContacts", Param("limit", limit), Param("search", search), Param("after", after))
  }
}

object Scraper extends Service {
  object internal {
    def asyncScrapeArticle() = ServiceRoute(POST, s"/internal/scraper/asyncScrape")
    def asyncScrapeArticleWithInfo() = ServiceRoute(POST, s"/internal/scraper/asyncScrapeWithInfo")
    def asyncScrapeArticleWithRequest() = ServiceRoute(POST, s"/internal/scraper/asyncScrapeWithRequest")
    def scheduleScrape() = ServiceRoute(POST, s"/internal/scraper/scheduleScrape")
    def scheduleScrapeWithRequest() = ServiceRoute(POST, s"/internal/scraper/scheduleScrapeWithRequest")
    def getBasicArticle(url:String) = ServiceRoute(GET, s"/internal/scraper/getBasicArticle", Param("url", url))
    def getBasicArticleP() = ServiceRoute(POST, s"/internal/scraper/getBasicArticleP")
    def getBasicArticleWithExtractor() = ServiceRoute(POST, s"/internal/scraper/getBasicArticleWithExtractor")
  }
}

object Common {
  object internal {
    def benchmarksResults() = ServiceRoute(GET, "/internal/benchmark")
    def version() = ServiceRoute(GET, "/internal/version")
  }
}

