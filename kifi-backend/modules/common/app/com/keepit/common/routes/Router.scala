package com.keepit.common.routes

import com.keepit.common.db.{ Id, ExternalId, State, SurrogateExternalId, SequenceNumber }
import com.keepit.curator.model.LibraryRecoSelectionParams
import com.keepit.model._
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.model.ArticleInfo
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.search.SearchConfigExperiment
import java.net.URLEncoder
import com.keepit.common.strings.UTF8
import com.keepit.search.index.message.ThreadContent
import com.keepit.eliza.model.MessageHandle
import com.keepit.cortex.core.{ StatModel, ModelVersion }
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.mail.EmailAddress
import com.keepit.abook.model.{ IngestableContact, EmailAccountInfo }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.social.SocialNetworkType

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
  implicit def externalIdSurrogateToParam[T <: SurrogateExternalId](i: T) = ParamValue(Some(i.id))
  implicit def idToParam[T](i: Id[T]) = ParamValue(Some(i.id.toString))
  implicit def optionToParam[T](i: Option[T])(implicit e: T => ParamValue) = i.map(e) getOrElse ParamValue(None)
  implicit def seqNumToParam[T](seqNum: SequenceNumber[T]) = ParamValue(Some(seqNum.value.toString))
  implicit def modelVersionToParam[M <: StatModel](modelVersion: ModelVersion[M]) = ParamValue(Some(modelVersion.version.toString))
  implicit def emailToParam(emailAddress: EmailAddress) = ParamValue(Some(emailAddress.address))
  implicit def userValueNameToParam(userValueName: UserValueName) = ParamValue(Some(userValueName.name))
  implicit def dateTimeToParam(dateTime: DateTime) = ParamValue(Some(dateTime.toStandardTimeString))
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
    def getPrimaryEmailAddressForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getPrimaryEmailAddressForUsers")
    def getCollectionIdsByExternalIds(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/collectionIdsByExternalIds", Param("ids", ids))
    def getUserOpt(id: ExternalId[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserOpt", Param("id", id))
    def getUserExperiments(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserExperiments", Param("id", id))
    def getExperimentsByUserIds() = ServiceRoute(POST, "/internal/shoebox/database/getExperimentsByUserIds")
    def getExperimentGenerators() = ServiceRoute(GET, "/internal/shoebox/database/getExperimentGenerators")
    def getUsersByExperiment(experiment: ExperimentType) = ServiceRoute(GET, "/internal/shoebox/database/getUsersByExperiment", Param("experiment", experiment.value))
    def getConnectedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getConnectedUsers", Param("userId", userId))
    def getBrowsingHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/browsingHistory", Param("userId", userId))
    def getClickHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/clickHistory", Param("userId", userId))
    def getBookmarks(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmark", Param("userId", userId))
    def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getLatestKeep() = ServiceRoute(POST, "/internal/shoebox/database/getLatestKeep")
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def sendMailToUser() = ServiceRoute(POST, "/internal/shoebox/database/sendMailToUser")
    def processAndSendMail() = ServiceRoute(POST, "/internal/shoebox/database/processAndSendMail")
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
    def getSessionByExternalId(sessionId: UserSessionExternalId) = ServiceRoute(GET, "/internal/shoebox/database/sessionViewByExternalId", Param("sessionId", sessionId))
    def getSearchFriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/searchFriends", Param("userId", userId))
    def getUnfriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/unfriends", Param("userId", userId))
    def logEvent() = ServiceRoute(POST, "/internal/shoebox/logEvent")
    def createDeepLink() = ServiceRoute(POST, "/internal/shoebox/database/createDeepLink")
    def getDeepUrl = ServiceRoute(POST, "/internal/shoebox/database/getDeepUrl")
    def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedUriUpdates", Param("lowSeq", lowSeq.value), Param("highSeq", highSeq.value))
    def kifiHit() = ServiceRoute(POST, "/internal/shoebox/database/kifiHit")
    def getHelpRankInfo() = ServiceRoute(POST, "/internal/shoebox/database/getHelpRankInfo")
    def assignScrapeTasks(zkId: Long, max: Int) = ServiceRoute(GET, "/internal/shoebox/database/assignScrapeTasks", Param("zkId", zkId), Param("max", max))
    def saveScrapeInfo() = ServiceRoute(POST, "/internal/shoebox/database/saveScrapeInfo")
    def updateNormalizedURI(uriId: Id[NormalizedURI]) = ServiceRoute(POST, "/internal/shoebox/database/updateNormalizedURI", Param("uriId", uriId))
    def getProxy(url: String) = ServiceRoute(GET, "/internal/shoebox/database/getProxy", Param("url"))
    def getProxyP() = ServiceRoute(POST, "/internal/shoebox/database/getProxyP")
    def getFriendRequestRecipientIdBySender(senderId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getFriendRequestRecipientIdBySender", Param("senderId", senderId))
    def getUserValue(userId: Id[User], key: UserValueName) = ServiceRoute(GET, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def setUserValue(userId: Id[User], key: UserValueName) = ServiceRoute(POST, "/internal/shoebox/database/userValue", Param("userId", userId), Param("key", key))
    def getUserSegment(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/userSegment", Param("userId", userId))
    def getExtensionVersion(installationId: ExternalId[KifiInstallation]) = ServiceRoute(GET, "/internal/shoebox/database/extensionVersion", Param("installationId", installationId))
    def triggerRawKeepImport() = ServiceRoute(POST, "/internal/shoebox/database/triggerRawKeepImport")
    def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]) = ServiceRoute(POST, "/internal/shoebox/database/triggerSocialGraphFetch", Param("id", socialUserInfoId))
    def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserConnectionsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getSearchFriendsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def allURLPatternRules() = ServiceRoute(GET, "/internal/shoebox/database/urlPatternRules")
    def getUserImageUrl(id: Id[User], width: Int) = ServiceRoute(GET, "/internal/shoebox/image/getUserImageUrl", Param("id", id), Param("width", width))
    def getCandidateURIs() = ServiceRoute(POST, "/internal/shoebox/database/getCandidateURIs")
    def getUnsubscribeUrlForEmail(email: EmailAddress) = ServiceRoute(GET, "/internal/shoebox/email/getUnsubscribeUrlForEmail", Param("email", email))
    def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialConnections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialUserInfos", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getEmailAccountUpdates", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getKeepsAndTagsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]) = ServiceRoute(GET, "/internal/shoebox/database/getLapsedUsersForDelighted", Param("maxCount", maxCount), Param("skipCount", skipCount), Param("after", after), Param("before", before))
    def getAllFakeUsers() = ServiceRoute(GET, "/internal/shoebox/database/getAllFakeUsers")
    def getInvitations(senderId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getInvitations", Param("senderId", senderId))
    def getSocialConnections(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getSocialConnections", Param("userId", userId))
    def addInteractions(userId: Id[User]) = ServiceRoute(POST, "/internal/shoebox/user/addInteractions", Param("userId", userId))
    def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getLibrariesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getDetailedLibrariesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getLibraryMembershipsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def canViewLibrary() = ServiceRoute(POST, "/internal/shoebox/libraries/canView")
    def newKeepsInLibraryForEmail(userId: Id[User], max: Int) = ServiceRoute(GET, "/internal/shoebox/database/newKeepsInLibraryForEmail", Param("userId", userId), Param("max", max))
    def getBasicKeeps(userId: Id[User]) = ServiceRoute(POST, "/internal/shoebox/database/getBasicKeeps", Param("userId", userId))
    def getBasicLibraryStatistics() = ServiceRoute(POST, "/internal/shoebox/database/getBasicLibraryStatistics")
    def getBasicLibraryDetails() = ServiceRoute(POST, "/internal/shoebox/database/getBasicLibraryDetails")
    def getKeepCounts() = ServiceRoute(POST, "/internal/shoebox/database/getKeepCounts")
    def getLibraryImageUrls() = ServiceRoute(POST, "/internal/shoebox/database/getLibraryImageUrls")
    def getKeepImages() = ServiceRoute(POST, "/internal/shoebox/database/getKeepImages")
    def getLibrariesWithWriteAccess(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getLibrariesWithWriteAccess", Param("userId", userId))
    def getUserActivePersonas(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/user/activePersonas", Param("userId", userId))
  }
}

object Search extends Service {
  object internal {
    def updateBrowsingHistory(id: Id[User]) = ServiceRoute(POST, s"/internal/search/events/browsed/${id.id}")
    def warmUpUser(id: Id[User]) = ServiceRoute(GET, s"/internal/search/warmUp/${id.id}")
    def updateKeepIndex() = ServiceRoute(GET, "/internal/search/updateKeepIndex")
    def updateLibraryIndex() = ServiceRoute(GET, "/internal/search/updateLibraryIndex")
    def searchUpdate() = ServiceRoute(POST, "/internal/search/index/update")
    def searchReindex() = ServiceRoute(POST, "/internal/search/index/reindex")
    def getSequenceNumber() = ServiceRoute(GET, "/internal/search/index/sequenceNumber")
    def userReindex() = ServiceRoute(POST, "/internal/search/user/reindex")
    def refreshSearcher() = ServiceRoute(POST, "/internal/search/index/refreshSearcher")
    def refreshPhrases() = ServiceRoute(POST, "/internal/search/index/refreshPhrases")
    def searchDumpLuceneDocument(id: Id[NormalizedURI], deprecated: Boolean) = ServiceRoute(POST, s"/internal/search/index/dumpDoc/${id.id}", Param("deprecated", deprecated))
    def getLibraryDocument() = ServiceRoute(POST, s"/internal/search/index/library/document")
    def searchKeeps(userId: Id[User], query: String) = ServiceRoute(POST, "/internal/search/search/keeps", Param("userId", userId), Param("query", query))
    def searchUsers() = ServiceRoute(POST, "/internal/search/search/users")
    def userTypeahead() = ServiceRoute(POST, "/internal/search/search/userTypeahead")
    def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String], debug: Option[String]) =
      ServiceRoute(GET, "/internal/search/search/uri/explain", Param("query", query), Param("userId", userId), Param("uriId", uriId), Param("lang", lang), Param("debug", debug))
    def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean) =
      ServiceRoute(GET, "/internal/search/search/library/explain", Param("query", query), Param("userId", userId), Param("libraryId", libraryId), Param("acceptLangs", acceptLangs.mkString(",")), Param("debug", debug), Param("disablePrefixSearch", disablePrefixSearch))
    def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean) =
      ServiceRoute(GET, "/internal/search/search/user/explain", Param("query", query), Param("userId", userId), Param("resultUserId", resultUserId), Param("acceptLangs", acceptLangs.mkString(",")), Param("debug", debug), Param("disablePrefixSearch", disablePrefixSearch))
    def showUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}")
    def setUserConfig(id: Id[User]) = ServiceRoute(POST, s"/internal/search/searchConfig/${id.id}/set")
    def resetUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}/reset")
    def getSearchDefaultConfig = ServiceRoute(GET, "/internal/search/defaultSearchConfig/defaultSearchConfig")

    def searchWithConfig() = ServiceRoute(POST, "/internal/searchWithConfig")

    def indexInfoList() = ServiceRoute(GET, "/internal/search/indexInfo/listAll")
    def updateUserGraph() = ServiceRoute(POST, "/internal/search/userGraph/update")
    def updateSearchFriendGraph() = ServiceRoute(POST, "/internal/search/searchFriendGraph/update")
    def reindexUserGraphs() = ServiceRoute(POST, "/internal/search/userGraphs/reindex")
    def updateUserIndex() = ServiceRoute(POST, "/internal/search/user/update")
    def getFeeds(userId: Id[User], limit: Int) = ServiceRoute(GET, "/internal/search/feed", Param("userId", userId), Param("limit", limit))
    def searchMessages(userId: Id[User], query: String, page: Int = 0) = ServiceRoute(GET, "/internal/search/searchMessages", Param("userId", userId), Param("query", query), Param("page", page))
    def augmentation() = ServiceRoute(POST, "/internal/search/augmentation")
    def augment() = ServiceRoute(POST, "/internal/search/augment")

    def distSearch() = ServiceRoute(POST, "/internal/search/dist/search")
    def distSearchUris() = ServiceRoute(POST, "/internal/search/dist/search/uri")
    def distLangFreqs() = ServiceRoute(POST, "/internal/search/dist/langFreqs")
    def distFeeds() = ServiceRoute(POST, "/internal/search/dist/feeds")
    def distAugmentation() = ServiceRoute(POST, "/internal/search/dist/augmentation")
    def distSearchLibraries() = ServiceRoute(POST, "/internal/search/dist/search/library")
    def distSearchUsers() = ServiceRoute(POST, "/internal/search/dist/search/user")
  }
}

object Eliza extends Service {
  object internal {
    def sendGeneralPushNotification() = ServiceRoute(POST, "/internal/eliza/sendGeneralPushNotification")
    def sendLibraryPushNotification() = ServiceRoute(POST, "/internal/eliza/sendLibraryPushNotification")
    def sendUserPushNotification() = ServiceRoute(POST, "/internal/eliza/sendUserPushNotification")
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
    def checkUrisDiscussed(userId: Id[User]) = ServiceRoute(POST, "/internal/eliza/checkUrisDiscussed", Param("userId", userId))
    def getUnreadNotifications(userId: Id[User], howMany: Int) = ServiceRoute(GET, "/internal/eliza/getUnreadNotifications", Param("userId", userId), Param("howMany", howMany))
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
    def postDelightedAnswer() = ServiceRoute(POST, s"/internal/heimdal/user/delighted/answer")
    def cancelDelightedSurvey() = ServiceRoute(POST, s"/internal/heimdal/user/delighted/cancel")

    def getPagedKeepDiscoveries(page: Int, size: Int) = ServiceRoute(GET, s"/internal/heimdal/data/keepDiscovery/page/$page", Param("size", size))
    def getDiscoveryCount() = ServiceRoute(GET, "/internal/heimdal/data/keepDiscovery/count")
    def getDiscoveryCountByKeeper(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/data/keepDiscovery/getDiscoveryCountByKeeper", Param("userId", userId))
    def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/data/keepDiscovery/getUriDiscoveriesWithCountsByKeeper", Param("userId", userId))
    def getDiscoveryCountsByURIs() = ServiceRoute(POST, "/internal/heimdal/data/keepDiscovery/getDiscoveryCountsByURIs")
    def getDiscoveryCountsByKeepIds() = ServiceRoute(POST, "/internal/heimdal/data/keepDiscovery/getDiscoveryCountsByKeepIds")

    def getPagedReKeeps(page: Int, size: Int) = ServiceRoute(GET, s"/internal/heimdal/data/reKeep/page/$page", Param("size", size))
    def getReKeepCount() = ServiceRoute(GET, "/internal/heimdal/data/reKeep/count")
    def getUriReKeepsWithCountsByKeeper(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/data/reKeep/getUriReKeepsWithCountsByKeeper", Param("userId", userId))
    def getReKeepCountsByURIs() = ServiceRoute(POST, "/internal/heimdal/data/reKeep/getReKeepCountsByURIs")
    def getReKeepCountsByKeepIds() = ServiceRoute(POST, "/internal/heimdal/data/reKeep/getReKeepCountsByKeepIds")

    def getUserReKeepsByDegree() = ServiceRoute(POST, "/internal/heimdal/helprank/getUserReKeepsByDegree")
    def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep]) = ServiceRoute(GET, "/internal/heimdal/helprank/getReKeepsByDegree", Param("keeperId", keeperId), Param("keepId", keepId))
    def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/heimdal/data/userKeepInfo/getReKeepCountsByUserUri", Param("userId", userId), Param("uriId", uriId))
    def getKeepAttributionInfo(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/helprank/getKeepAttributionInfo", Param("userId", userId))
    def getHelpRankInfo() = ServiceRoute(POST, "/internal/heimdal/helprank/getHelpRankInfo")
    def updateUserReKeepStats() = ServiceRoute(POST, "/internal/heimdal/helprank/updateUserReKeepStats")
    def updateUsersReKeepStats() = ServiceRoute(POST, "/internal/heimdal/helprank/updateUsersReKeepStats")
    def updateAllReKeepStats() = ServiceRoute(POST, "/internal/heimdal/helprank/updateAllReKeepStats")
    def processSearchHitAttribution() = ServiceRoute(POST, "/internal/heimdal/helprank/processSearchHitAttribution")
    def processKeepAttribution() = ServiceRoute(POST, "/internal/heimdal/helprank/processKeepAttribution")
    def getOwnerLibraryViewStats(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/data/libraryView", Param("userId", userId))
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
    def hideEmailFromUser(userId: Id[User], email: EmailAddress) = ServiceRoute(POST, s"/internal/abook/${userId.id}/hideEmailFromUser", Param("email", email))
    def getContactNameByEmail(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/getContactNameByEmail")
    def internKifiContacts(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/internKifiContacts")
    def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/prefixQuery", Param("q", query), Param("maxHits", maxHits))
    def getContactsByUser(userId: Id[User], page: Int, pageSize: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/getContacts", Param("page", page), Param("pageSize", pageSize))
    def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccountInfo], fetchSize: Int) = ServiceRoute(GET, "/internal/abook/database/getEmailAccountsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getContactsChanged(seqNum: SequenceNumber[IngestableContact], fetchSize: Int) = ServiceRoute(GET, "/internal/abook/database/getContactsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getUsersWithContact(email: EmailAddress) = ServiceRoute(GET, "/internal/abook/getUsersWithContact", Param("email", email))
    def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int, bePatient: Boolean) = ServiceRoute(GET, s"/internal/abook/${userId}/getFriendRecommendations", Param("offset", offset), Param("limit", limit), Param("bePatient", bePatient))
    def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId}/hideFriendRecommendation", Param("irrelevantUserId", irrelevantUserId))
    def getInviteRecommendations(userId: Id[User], offset: Int, limit: Int, networks: Set[SocialNetworkType]) = ServiceRoute(GET, s"/internal/abook/${userId}/getInviteRecommendations", Param("offset", offset), Param("limit", limit), Param("networks", networks.mkString(",")))
    def hideInviteRecommendation(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId}/hideInviteRecommendation")
    def getIrrelevantPeople(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/${userId}/getIrrelevantPeople")
  }
}

object Scraper extends Service {
  object internal {
    def status() = ServiceRoute(GET, s"/internal/scraper/status")
    def getBasicArticle() = ServiceRoute(POST, s"/internal/scraper/getBasicArticle")
    def getSignature() = ServiceRoute(POST, s"/internal/scraper/getSignature")
    def getPornDetectorModel() = ServiceRoute(GET, s"/internal/scraper/getPornDetectorModel")
    def detectPorn() = ServiceRoute(POST, s"/internal/scraper/pornDetector/detectPorn")
    def whitelist() = ServiceRoute(POST, s"/internal/scraper/pornDetector/whitelist")
  }
}

object Cortex extends Service {
  type LDAVersion = ModelVersion[DenseLDA]
  type LDAVersionOpt = Option[LDAVersion]

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

    def defulatLDAVersion() = ServiceRoute(GET, "/internal/cortex/lda/defaultVersion")
    def ldaNumOfTopics(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/numOfTopics", Param("version", version))
    def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/showTopics", Param("fromId", fromId), Param("toId", toId), Param("topN", topN), Param("version", version))
    def ldaWordTopic(word: String)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/wordTopic", Param("word", word), Param("version", version))
    def ldaDocTopic(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/docTopic", Param("version", version))
    def ldaConfigurations(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/confs", Param("version", version))
    def saveEdits(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/saveEdits", Param("version", version))
    def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userUriInterest", Param("userId", userId), Param("uriId", uriId), Param("version", version))
    def batchUserURIsInterests(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/batchUserUrisInterests", Param("version", version))
    def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userTopicMean", Param("userId", userId), Param("version", version))
    def sampleURIsForTopic(topicId: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/sampleURIs", Param("topicId", topicId), Param("version", version))
    def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/getSimilarUsers", Param("userId", userId), Param("topK", topK), Param("version", version))
    def unamedTopics(limit: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/unamedTopics", Param("limit", limit), Param("version", version))
    def getTopicNames(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/getTopicNames", Param("version", version))
    def explainFeed(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/explainFeed", Param("version", version))
    def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/libraryTopic", Param("libId", libId), Param("version", version))
    def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userLibraryScore", Param("userId", userId), Param("libId", libId), Param("version", version))
    def userLibrariesScores(userId: Id[User])(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/userLibrariesScores", Param("userId", userId), Param("version", version))
    def similarURIs(uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/similarURIs", Param("uriId", uriId), Param("version", version))
    def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/similarLibraries", Param("libId", libId), Param("limit", limit), Param("version", version))

    def getExistingPersonaFeature(personaId: Id[Persona])(implicit version: LDAVersion) = ServiceRoute(GET, "/internal/cortex/lda/getExistingPersonaFeature", Param("personaId", personaId), Param("version", version))
    def generatePersonaFeature(implicit version: LDAVersion) = ServiceRoute(POST, "/internal/cortex/lda/generatePersonaFeature", Param("version", version))
    def savePersonaFeature(implicit version: LDAVersion) = ServiceRoute(POST, "/internal/cortex/lda/savePersonaFeature", Param("version", version))
    def evaluatePersona(personaId: Id[Persona])(implicit version: LDAVersion) = ServiceRoute(GET, "/internal/cortex/lda/evaluatePersona", Param("personaId", personaId), Param("version", version))
    def trainPersonaFeature(personaId: Id[Persona])(implicit version: LDAVersion) = ServiceRoute(POST, "/internal/cortex/lda/trainPersonaFeature", Param("personaId", personaId), Param("version", version))

    def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/cortex/data/sparseLDAFeaturesChanged", Param("modelVersion", modelVersion), Param("seqNum", seqNum), Param("fetchSize", fetchSize))
  }
}

object Graph extends Service {
  object internal {
    def getGraphStatistics() = ServiceRoute(GET, "/internal/graph/statistics")
    def getGraphUpdaterState() = ServiceRoute(GET, "/internal/graph/state")
    def getGraphKinds() = ServiceRoute(GET, "/internal/graph/kinds")
    def wander() = ServiceRoute(POST, "/internal/graph/wander")
    def uriWandering(userId: Id[User], steps: Int) = ServiceRoute(GET, "/internal/graph/uriWandering", Param("userId", userId), Param("steps", steps))
    def getUriAndScores(userId: Id[User], avoidFirstDegreeConnections: Boolean) = ServiceRoute(GET, "/internal/graph/getUriAndScorePairs", Param("userId", userId), Param("avoidFirstDegreeConnections", avoidFirstDegreeConnections))
    def getUserAndScores(userId: Id[User], avoidFirstDegreeConnections: Boolean) = ServiceRoute(GET, "/internal/graph/getUserAndScorePairs", Param("userId", userId), Param("avoidFirstDegreeConnections", avoidFirstDegreeConnections))
    def getSociallyRelatedEntities(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedEntities", Param("userId", userId))
    def getSociallyRelatedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedUsers", Param("userId", userId))
    def getSociallyRelatedFacebookAccounts(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedFacebookAccounts", Param("userId", userId))
    def getSociallyRelatedLinkedInAccounts(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedLinkedInAccounts", Param("userId", userId))
    def getSociallyRelatedEmailAccounts(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedEmailAccounts", Param("userId", userId))
    def explainFeed() = ServiceRoute(POST, "/internal/graph/explainFeed")
  }
}

object Curator extends Service {
  object internal {
    def topRecos(userId: Id[User]) = ServiceRoute(POST, "/internal/curator/topRecos", Param("userId", userId))
    def topPublicRecos(userId: Option[Id[User]]) = ServiceRoute(GET, "/internal/curator/topPublicRecos", Param("userId", userId))
    def generalRecos() = ServiceRoute(GET, "/internal/curator/generalRecos")
    def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = ServiceRoute(POST, "/internal/curator/updateUriRecommendationFeedback", Param("userId", userId), Param("uriId", uriId))
    def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library]) = ServiceRoute(POST, "/internal/curator/updateLibraryRecommendationFeedback", Param("userId", userId), Param("libraryId", libraryId))
    def triggerEmailToUser(code: String, userId: Id[User]) = ServiceRoute(POST, "/internal/curator/triggerEmailToUser", Param("code", code), Param("userId", userId))
    def refreshUserRecos(userId: Id[User]) = ServiceRoute(POST, "/internal/curator/refreshUserRecos", Param("userId", userId))
    def topLibraryRecos(userId: Id[User], limit: Option[Int]) = ServiceRoute(POST, "/internal/curator/topLibraryRecos", Param("userId", userId), Param("limit", limit))
    def refreshLibraryRecos(userId: Id[User], await: Boolean) = ServiceRoute(POST, "/internal/curator/refreshLibraryRecos", Param("userId", userId), Param("await", await))
    def notifyLibraryRecosDelivered(userId: Id[User]) = ServiceRoute(POST, "/internal/curator/notifyLibraryRecosDelivered", Param("userId", userId))
    def ingestPersonaRecos(userId: Id[User], reverseIngestion: Boolean) = ServiceRoute(POST, "/internal/curator/ingestPersonaRecos", Param("userId", userId), Param("reverseIngestion", reverseIngestion))
    def examineUserFeedbackCounter(userId: Id[User]) = ServiceRoute(GET, "/internal/curator/examineUserFeedbackCounter", Param("userId", userId))
  }
}

object Rover extends Service {
  object internal {
    def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int) = ServiceRoute(GET, "/internal/rover/getShoeboxUpdates", Param("seq", seq), Param("limit", limit))
    def fetchAsap() = ServiceRoute(POST, "/internal/rover/fetchAsap")
    def getBestArticlesByUris() = ServiceRoute(POST, "/internal/rover/getBestArticlesByUris")
    def getArticleInfosByUris() = ServiceRoute(POST, "/internal/rover/getArticleInfosByUris")
    def getBestArticleSummaryByUris() = ServiceRoute(POST, "/internal/rover/getBestArticleSummaryByUris")
    def getImagesByUris() = ServiceRoute(POST, "/internal/rover/getImagesByUris")
    def getOrElseFetchArticleSummaryAndImages() = ServiceRoute(POST, "/internal/rover/getOrElseFetchArticleSummaryAndImages")
    def getOrElseFetchRecentArticle() = ServiceRoute(POST, "/internal/rover/getOrElseFetchRecentArticle")
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

