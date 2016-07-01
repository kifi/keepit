package com.keepit.common.routes

import com.keepit.common.db.{ Id, ExternalId, State, SurrogateExternalId, SequenceNumber }
import com.keepit.discussion.Message
import com.keepit.model._
import com.keepit.rover.model.ArticleInfo
import com.keepit.shoebox.model.IngestableUserIpAddress
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.search.SearchConfigExperiment
import java.net.URLEncoder
import com.keepit.common.strings.UTF8
import com.keepit.search.index.message.ThreadContent
import com.keepit.cortex.core.{ StatModel, ModelVersion }
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.mail.EmailAddress
import com.keepit.abook.model.{ IngestableContact, EmailAccountInfo }
import com.keepit.slack.models.SlackTeamId
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
object Param {
  implicit def fromTuple[T](keyValue: (String, T))(implicit toParamValue: T => ParamValue) = Param(keyValue._1, toParamValue(keyValue._2))
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

sealed abstract class Method(name: String)
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
    def getUserIdByIdentityId(providerId: String, id: String) = ServiceRoute(GET, "/internal/shoebox/auth/getUserIdByIdentityId", Param("providerId", providerId), Param("id", id))
    def getNormalizedURI(id: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURI", Param("id", id))
    def getNormalizedURIByURL() = ServiceRoute(POST, "/internal/shoebox/database/getNormalizedURIByURL")
    def getNormalizedUriByUrlOrPrenormalize() = ServiceRoute(POST, "/internal/shoebox/database/getNormalizedUriByUrlOrPrenormalize")
    def internNormalizedURI() = ServiceRoute(POST, "/internal/shoebox/database/internNormalizedURI")
    def getUsers(ids: String) = ServiceRoute(GET, "/internal/shoebox/database/getUsers", Param("ids", ids))
    def getUserIdsByExternalIds() = ServiceRoute(POST, "/internal/shoebox/database/userIdsByExternalIds")
    def getBasicUsers() = ServiceRoute(POST, "/internal/shoebox/database/getBasicUsers")
    def getRecipientsOnKeep(keepId: Id[Keep]) = ServiceRoute(GET, "/internal/shoebox/database/getRecipientsOnKeep", Param("keepId", keepId))
    def getEmailAddressesForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getEmailAddressesForUsers")
    def getEmailAddressForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getEmailAddressForUsers")
    def getUserOpt(id: ExternalId[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserOpt", Param("id", id))
    def getUserExperiments(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getUserExperiments", Param("id", id))
    def getExperimentsByUserIds() = ServiceRoute(POST, "/internal/shoebox/database/getExperimentsByUserIds")
    def getExperimentGenerators() = ServiceRoute(GET, "/internal/shoebox/database/getExperimentGenerators")
    def getUsersByExperiment(experiment: UserExperimentType) = ServiceRoute(GET, "/internal/shoebox/database/getUsersByExperiment", Param("experiment", experiment.value))
    def getConnectedUsers(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getConnectedUsers", Param("userId", userId))
    def getBrowsingHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/browsingHistory", Param("userId", userId))
    def getClickHistoryFilter(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/tracker/clickHistory", Param("userId", userId))
    def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/changedBookmark", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/bookmarkByUriUser", Param("uriId", uriId), Param("userId", userId))
    def getLatestKeep() = ServiceRoute(POST, "/internal/shoebox/database/getLatestKeep")
    def persistServerSearchEvent() = ServiceRoute(POST, "/internal/shoebox/persistServerSearchEvent")
    def sendMail() = ServiceRoute(POST, "/internal/shoebox/database/sendMail")
    def sendMailToUser() = ServiceRoute(POST, "/internal/shoebox/database/sendMailToUser")
    def processAndSendMail() = ServiceRoute(POST, "/internal/shoebox/database/processAndSendMail")
    def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getPhrasesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableUris", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableUrisWithContent(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableUrisWithContent", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getHighestUriSeq() = ServiceRoute(GET, "/internal/shoebox/database/getHighestUriSeq")
    def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getUserIndexable", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getActiveExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getActiveExperiments")
    def getExperiments() = ServiceRoute(GET, "/internal/shoebox/database/getExperiments")
    def getExperiment(id: Id[SearchConfigExperiment]) = ServiceRoute(GET, "/internal/shoebox/database/getExperiment", Param("id", id))
    def saveExperiment = ServiceRoute(POST, "/internal/shoebox/database/saveExperiment")
    def getSocialUserInfosByUserId(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/socialUserInfosByUserId", Param("id", id))
    def getPrimaryOrg(id: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getPrimaryOrg", Param("id", id))
    def getSessionByExternalId(sessionId: UserSessionExternalId) = ServiceRoute(GET, "/internal/shoebox/database/sessionViewByExternalId", Param("sessionId", sessionId))
    def getSearchFriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/searchFriends", Param("userId", userId))
    def getUnfriends(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/unfriends", Param("userId", userId))
    def logEvent() = ServiceRoute(POST, "/internal/shoebox/logEvent")
    def createDeepLink() = ServiceRoute(POST, "/internal/shoebox/database/createDeepLink")
    def getDeepUrl = ServiceRoute(POST, "/internal/shoebox/database/getDeepUrl")
    def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedUriUpdates", Param("lowSeq", lowSeq.value), Param("highSeq", highSeq.value))
    def kifiHit() = ServiceRoute(POST, "/internal/shoebox/database/kifiHit")
    def getHelpRankInfo() = ServiceRoute(POST, "/internal/shoebox/database/getHelpRankInfo")
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
    def getUnsubscribeUrlForEmail(email: EmailAddress) = ServiceRoute(GET, "/internal/shoebox/email/getUnsubscribeUrlForEmail", Param("email", email))
    def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialConnections", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIndexableSocialUserInfos", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getEmailAccountUpdates", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getKeepsAndTagsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getCrossServiceKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getCrossServiceKeepsAndTagsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getAllFakeUsers() = ServiceRoute(GET, "/internal/shoebox/database/getAllFakeUsers")
    def getInvitations(senderId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getInvitations", Param("senderId", senderId))
    def getSocialConnections(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getSocialConnections", Param("userId", userId))
    def addInteractions(userId: Id[User]) = ServiceRoute(POST, "/internal/shoebox/user/addInteractions", Param("userId", userId))
    def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getLibrariesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getDetailedLibrariesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getLibraryMembershipsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def canViewLibrary() = ServiceRoute(POST, "/internal/shoebox/database/canViewLibrary")
    def getPersonalKeeps(userId: Id[User]) = ServiceRoute(POST, "/internal/shoebox/database/getPersonalKeeps", Param("userId", userId))
    def getBasicLibraryDetails() = ServiceRoute(POST, "/internal/shoebox/database/getBasicLibraryDetails")
    def getLibraryCardInfos() = ServiceRoute(POST, "/internal/shoebox/database/getLibraryCardInfos")
    def getKeepCounts() = ServiceRoute(POST, "/internal/shoebox/database/getKeepCounts")
    def getKeepImages() = ServiceRoute(POST, "/internal/shoebox/database/getKeepImages")
    def getLibrariesWithWriteAccess(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getLibrariesWithWriteAccess", Param("userId", userId))
    def getUserActivePersonas(userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/user/activePersonas", Param("userId", userId))
    def getLibraryURIS(libId: Id[Library]) = ServiceRoute(GET, "/internal/shoebox/database/dumpLibraryURIIds", Param("libId", libId))
    def internDomainsByDomainNames() = ServiceRoute(POST, "/internal/shoebox/database/internDomainsByDomainNames")
    def getIngestableOrganizations(seqNum: SequenceNumber[Organization], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIngestableOrganizations", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIngestableOrganizationMemberships(seqNum: SequenceNumber[OrganizationMembership], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIngestableOrganizationMemberships", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIngestableOrganizationMembershipCandidates(seqNum: SequenceNumber[OrganizationMembershipCandidate], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIngestableOrganizationMembershipCandidates", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getIngestableUserIpAddresses(sequenceNumber: SequenceNumber[IngestableUserIpAddress], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIngestableUserIpAddresses", Param("seqNum", sequenceNumber), Param("fetchSize", fetchSize))
    def getOrganizationMembers(orgId: Id[Organization]) = ServiceRoute(GET, "/internal/shoebox/database/getOrganizationMembers", Param("orgId", orgId))
    def getOrganizationInviteViews(orgId: Id[Organization]) = ServiceRoute(GET, "/internal/shoebox/database/getOrganizationInviteViews", Param("orgId", orgId))
    def hasOrganizationMembership(orgId: Id[Organization], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/hasOrganizationMembership", Param("orgId", orgId), Param("userId", userId))
    def getIngestableOrganizationDomainOwnerships(seqNum: SequenceNumber[OrganizationDomainOwnership], fetchSize: Int) = ServiceRoute(GET, "/internal/shoebox/database/getIngestableOrganizationDomainOwnerships", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getOrganizationsForUsers() = ServiceRoute(POST, "/internal/shoebox/database/getOrganizationsForUsers")
    def getOrgTrackingValues(orgId: Id[Organization]) = ServiceRoute(GET, "/internal/shoebox/database/getOrgTrackingValues", Param("orgId", orgId))
    def getCrossServiceKeepsByIds = ServiceRoute(POST, "/internal/shoebox/database/getCrossServiceKeepsByIds")
    def getDiscussionKeepsByIds = ServiceRoute(POST, "/internal/shoebox/database/getDiscussionKeepsByIds")
    def getBasicOrganizationsByIds() = ServiceRoute(POST, "/internal/shoebox/database/getBasicOrganizationsByIds")
    def getLibraryMembershipView(libraryId: Id[Library], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getLibraryMembershipView", Param("libraryId", libraryId), Param("userId", userId))
    def getOrganizationUserRelationship(orgId: Id[Organization], userId: Id[User]) = ServiceRoute(GET, "/internal/shoebox/database/getOrganizationUserRelationship", Param("orgId", orgId), Param("userId", userId))
    def getUserPermissionsByOrgId() = ServiceRoute(POST, "/internal/shoebox/database/getUserPermissionsByOrgId")
    def getIntegrationsBySlackChannel() = ServiceRoute(POST, "/internal/shoebox/database/getIntegrationsBySlackChannel")
    def getSourceAttributionForKeeps() = ServiceRoute(POST, "/internal/shoebox/database/getSourceAttributionForKeeps")
    def getRelevantKeepsByUserAndUri() = ServiceRoute(POST, "/internal/shoebox/database/getRelevantKeepsByUserAndUri")
    def getPersonalKeepRecipientsOnUris() = ServiceRoute(POST, "/internal/shoebox/database/getPersonalKeepRecipientsOnUris")
    def getSlackTeamIds() = ServiceRoute(POST, "/internal/shoebox/database/getSlackTeamIds")
    def getSlackTeamInfo(slackTeamId: SlackTeamId) = ServiceRoute(GET, "/internal/shoebox/database/getSlackTeamInfo", Param("slackTeamId", slackTeamId.value))
    def internKeep() = ServiceRoute(POST, "/internal/shoebox/database/internKeep")
    def editRecipientsOnKeep(editorId: Id[User], keepId: Id[Keep]) = ServiceRoute(POST, "/internal/shoebox/database/editRecipientsOnKeep", Param("editorId", editorId), Param("keepId", keepId))
    def registerMessageOnKeep() = ServiceRoute(POST, "/internal/shoebox/database/registerMessageOnKeep")
    def persistModifyRecipients() = ServiceRoute(POST, "/internal/shoebox/database/persistModifyRecipients")
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
    def searchUsersByName() = ServiceRoute(POST, "/internal/search/search/users/name")
    def userTypeahead() = ServiceRoute(POST, "/internal/search/search/userTypeahead")
    def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Id[Library]], lang: Option[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) =
      ServiceRoute(GET, "/internal/search/search/uri/explain", Param("query", query), Param("userId", userId), Param("uriId", uriId), Param("libraryId", libraryId), Param("lang", lang), Param("debug", debug), Param("disablePrefixSearch", disablePrefixSearch), Param("disableFullTextSearch", disableFullTextSearch))
    def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) =
      ServiceRoute(GET, "/internal/search/search/library/explain", Param("query", query), Param("userId", userId), Param("libraryId", libraryId), Param("acceptLangs", acceptLangs.mkString(",")), Param("debug", debug), Param("disablePrefixSearch", disablePrefixSearch), Param("disableFullTextSearch", disableFullTextSearch))
    def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) =
      ServiceRoute(GET, "/internal/search/search/user/explain", Param("query", query), Param("userId", userId), Param("resultUserId", resultUserId), Param("acceptLangs", acceptLangs.mkString(",")), Param("debug", debug), Param("disablePrefixSearch", disablePrefixSearch), Param("disableFullTextSearch", disableFullTextSearch))
    def showUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}")
    def setUserConfig(id: Id[User]) = ServiceRoute(POST, s"/internal/search/searchConfig/${id.id}/set")
    def resetUserConfig(id: Id[User]) = ServiceRoute(GET, s"/internal/search/searchConfig/${id.id}/reset")
    def getSearchDefaultConfig = ServiceRoute(GET, "/internal/search/defaultSearchConfig/defaultSearchConfig")

    def searchWithConfig() = ServiceRoute(POST, "/internal/searchWithConfig")

    def indexInfoList() = ServiceRoute(GET, "/internal/search/indexInfo/listAll")
    def versions() = ServiceRoute(GET, "/internal/search/index/versions")
    def updateUserGraph() = ServiceRoute(POST, "/internal/search/userGraph/update")
    def updateSearchFriendGraph() = ServiceRoute(POST, "/internal/search/searchFriendGraph/update")
    def reindexUserGraphs() = ServiceRoute(POST, "/internal/search/userGraphs/reindex")
    def updateUserIndex() = ServiceRoute(POST, "/internal/search/user/update")
    def getFeeds(userId: Id[User], limit: Int) = ServiceRoute(GET, "/internal/search/feed", Param("userId", userId), Param("limit", limit))
    def searchMessages(userId: Id[User], query: String, page: Int = 0) = ServiceRoute(GET, "/internal/search/searchMessages", Param("userId", userId), Param("query", query), Param("page", page))
    def augmentation() = ServiceRoute(POST, "/internal/search/augmentation")
    def augment() = ServiceRoute(POST, "/internal/search/augment")

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
    def sendOrgPushNotification() = ServiceRoute(POST, "/internal/eliza/sendOrgPushNotification")
    def sendToUserNoBroadcast() = ServiceRoute(POST, "/internal/eliza/sendToUserNoBroadcast")
    def sendToUser() = ServiceRoute(POST, "/internal/eliza/sendToUser")
    def sendToAllUsers() = ServiceRoute(POST, "/internal/eliza/sendToAllUsers")
    def connectedClientCount() = ServiceRoute(GET, "/internal/eliza/connectedClientCount")
    def sendNotificationEvents() = ServiceRoute(POST, "/internal/eliza/notifications/send")
    def completeNotification() = ServiceRoute(POST, "/internal/eliza/notifications/complete")
    def importThread() = ServiceRoute(POST, "/internal/eliza/importThread")
    def getUserThreadStats(userId: Id[User]) = ServiceRoute(GET, "/internal/eliza/getUserThreadStats", Param("userId", userId))
    def getNonUserThreadMuteInfo(publicId: String) = ServiceRoute(GET, "/internal/eliza/getNonUserThreadMuteInfo", Param("publicId", publicId))
    def setNonUserThreadMuteState(publicId: String, muted: Boolean) = ServiceRoute(POST, "/internal/eliza/setNonUserThreadMuteState", Param("publicId", publicId), Param("muted", muted))
    def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long) = ServiceRoute(GET, "/internal/eliza/getThreadContentForIndexing", Param("sequenceNumber", sequenceNumber), Param("maxBatchSize", maxBatchSize))
    def getKeepIngestionSequenceNumber = ServiceRoute(GET, "/internal/eliza/getKeepIngestionSequenceNumber")
    def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]) = ServiceRoute(GET, "/internal/eliza/keepAttribution", Param("userId", userId), Param("uriId", uriId))
    def areUsersOnline(users: Seq[Id[User]]) = ServiceRoute(GET, "/internal/eliza/areUsersOnline", Param("users", users.map(_.id.toString).mkString(",")))
    def getUnreadNotifications(userId: Id[User], howMany: Int) = ServiceRoute(GET, "/internal/eliza/getUnreadNotifications", Param("userId", userId), Param("howMany", howMany))
    def getSharedThreadsForGroupByWeek = ServiceRoute(POST, "/internal/eliza/sharedThreadsForGroupByWeek")
    def getAllThreadsForGroupByWeek = ServiceRoute(POST, "/internal/eliza/allThreadsForGroupByWeek")
    def getParticipantsByThreadExtId(threadExtId: String) = ServiceRoute(GET, "/internal/eliza/getParticipantsByThreadExtId", Param("threadId", threadExtId))

    def getCrossServiceMessages = ServiceRoute(POST, "/internal/eliza/getCrossServiceMessages")
    def getDiscussionsForKeeps = ServiceRoute(POST, "/internal/eliza/getDiscussionsForKeeps")
    def getCrossServiceDiscussionsForKeeps = ServiceRoute(POST, "/internal/eliza/getCrossServiceDiscussionsForKeeps")
    def markKeepsAsReadForUser() = ServiceRoute(POST, "/internal/eliza/markKeepsAsReadForUser")
    def sendMessageOnKeep() = ServiceRoute(POST, "/internal/eliza/sendMessageOnKeep")
    def getMessagesOnKeep = ServiceRoute(POST, "/internal/eliza/getMessagesOnKeep")
    def getMessageCountsForKeeps = ServiceRoute(POST, "/internal/eliza/getMessageCountsForKeeps")
    def getChangedMessagesFromKeeps = ServiceRoute(POST, "/internal/eliza/getChangedMessagesFromKeeps")
    def getElizaKeepStream(userId: Id[User], limit: Int, beforeId: Option[Id[Keep]], filter: ElizaFeedFilter) = ServiceRoute(GET, "/internal/eliza/getElizaKeepStream", Param("userId", userId), Param("limit", limit), Param("beforeId", beforeId.map(_.id)), Param("filter", filter.kind))
    def editMessage() = ServiceRoute(POST, "/internal/eliza/editMessage")
    def deleteMessage() = ServiceRoute(POST, "/internal/eliza/deleteMessage")
    def keepHasAccessToken(keepId: Id[Keep], accessToken: String) = ServiceRoute(GET, "/internal/eliza/keepHasAccessToken", Param("keepId", keepId), Param("accessToken", accessToken))
    def deleteThreadsForKeeps() = ServiceRoute(POST, "/internal/eliza/deleteThreadsForKeeps")
    def getMessagesChanged(seqNum: SequenceNumber[Message], fetchSize: Int) = ServiceRoute(GET, "/internal/eliza/getMessagesChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def convertNonUserThreadToUserThread(userId: Id[User], accessToken: String) = ServiceRoute(POST, "/internal/eliza/convertNonUserThreadToUserThread", Param("userId", userId), Param("accessToken", accessToken))

    def internEmptyThreadsForKeeps = ServiceRoute(POST, "/internal/eliza/internEmptyThreadsForKeeps")
    def modifyRecipientsAndSendEvent = ServiceRoute(POST, "/internal/eliza/modifyRecipientsAndSendEvent")
    def pageSystemMessages(fromId: Id[Message], pageSize: Int) = ServiceRoute(GET, "/internal/eliza/pageSystemMessages", Param("fromId", fromId.id), Param("pageSize", pageSize))
    def rpbTest = ServiceRoute(POST, "/internal/eliza/rpbTest")

    def getEmailParticipantsForKeeps() = ServiceRoute(POST, "/internal/eliza/getEmailParticipantsForKeeps")
    def getInitialRecipientsByKeepId() = ServiceRoute(POST, "/internal/eliza/getInitialRecipientsByKeepId")
  }
}

object Heimdal extends Service {
  object internal {
    def deleteUser(userId: Id[User]) = ServiceRoute(GET, s"/internal/heimdal/user/delete", Param("userId", userId))
    def incrementUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/increment", Param("userId", userId))
    def setUserProperties(userId: Id[User]) = ServiceRoute(POST, s"/internal/heimdal/user/set", Param("userId", userId))
    def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = ServiceRoute(GET, "/internal/heimdal/user/alias", Param("userId", userId), Param("externalId", externalId))

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
    def getEligibleGratDatas() = ServiceRoute(POST, "/internal/heimdal/data/getEligibleGratDatas")
    def getGratData(userId: Id[User]) = ServiceRoute(GET, "/internal/heimdal/data/getGratData", Param("userId", userId))
    def getGratDatas() = ServiceRoute(POST, "/internal/heimdal/data/getGratDatas")
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
    def hideEmailFromUser(userId: Id[User], email: EmailAddress) = ServiceRoute(POST, s"/internal/abook/${userId.id}/hideEmailFromUser", Param("email", email))
    def getContactNameByEmail(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/getContactNameByEmail")
    def internKifiContacts(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/${userId.id}/internKifiContacts")
    def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/prefixQuery", Param("q", query), Param("maxHits", maxHits))
    def getContactsByUser(userId: Id[User], page: Int, pageSize: Option[Int]) = ServiceRoute(GET, s"/internal/abook/${userId}/getContacts", Param("page", page), Param("pageSize", pageSize))
    def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccountInfo], fetchSize: Int) = ServiceRoute(GET, "/internal/abook/database/getEmailAccountsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getContactsChanged(seqNum: SequenceNumber[IngestableContact], fetchSize: Int) = ServiceRoute(GET, "/internal/abook/database/getContactsChanged", Param("seqNum", seqNum), Param("fetchSize", fetchSize))
    def getUsersWithContact(email: EmailAddress) = ServiceRoute(GET, "/internal/abook/getUsersWithContact", Param("email", email))
    def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int) = ServiceRoute(GET, s"/internal/abook/user/${userId}/getFriendRecommendations", Param("offset", offset), Param("limit", limit))
    def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]) = ServiceRoute(POST, s"/internal/abook/user/${userId}/hideFriendRecommendation", Param("irrelevantUserId", irrelevantUserId))
    def getInviteRecommendationsForUser(userId: Id[User], offset: Int, limit: Int, networks: Set[SocialNetworkType]) = ServiceRoute(GET, s"/internal/abook/user/${userId}/getInviteRecommendations", Param("offset", offset), Param("limit", limit), Param("networks", networks.mkString(",")))
    def hideInviteRecommendationForUser(userId: Id[User]) = ServiceRoute(POST, s"/internal/abook/user/${userId}/hideInviteRecommendation")
    def getIrrelevantPeopleForUser(userId: Id[User]) = ServiceRoute(GET, s"/internal/abook/user/${userId}/getIrrelevantPeople")
    def getIrrelevantPeopleForOrg(orgId: Id[Organization]) = ServiceRoute(GET, s"/internal/abook/org/${orgId}/getIrrelevantPeople")
    def getRecommendationsForOrg(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], offset: Int, limit: Int) = ServiceRoute(GET, s"/internal/abook/org/$orgId/getRecommendations", Param("orgId", orgId), Param("viewerIdOpt", viewerIdOpt.map(_.id)), Param("offset", offset), Param("limit", limit))
    def hideUserRecommendationForOrg(orgId: Id[Organization], memberId: Id[User], irrelevantUserId: Id[User]) = ServiceRoute(POST, s"/internal/abook/org/$orgId/hideUserRecommendation")
    def hideEmailRecommendationForOrg(orgId: Id[Organization]) = ServiceRoute(POST, s"/internal/abook/org/${orgId}/hideEmailRecommendation")
    def getOrganizationRecommendationsForUser(userId: Id[User], offset: Int, limit: Int) = ServiceRoute(GET, s"/internal/abook/user/${userId}/getOrganizationRecommendations", Param("offset", offset), Param("limit", limit))
    def hideOrganizationRecommendationForUser(userId: Id[User], irrelevantOrganizationId: Id[Organization]) = ServiceRoute(POST, s"/internal/abook/user/${userId}/hideOrganizationRecommendation", Param("irrelevantOrganizationId", irrelevantOrganizationId))
  }
}

object Scraper extends Service {
  object internal {
    def status() = ServiceRoute(GET, s"/internal/scraper/status")
    def getBasicArticle() = ServiceRoute(POST, s"/internal/scraper/getBasicArticle")
    def getSignature() = ServiceRoute(POST, s"/internal/scraper/getSignature")
  }
}

object Cortex extends Service {
  type LDAVersion = ModelVersion[DenseLDA]
  type LDAVersionOpt = Option[LDAVersion]

  object internal {
    def defulatLDAVersion() = ServiceRoute(GET, "/internal/cortex/lda/defaultVersion")
    def ldaNumOfTopics(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/numOfTopics", Param("version", version))
    def ldaShowTopics(fromId: Int, toId: Int, topN: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/showTopics", Param("fromId", fromId), Param("toId", toId), Param("topN", topN), Param("version", version))
    def ldaWordTopic(word: String)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/wordTopic", Param("word", word), Param("version", version))
    def ldaDocTopic(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/docTopic", Param("version", version))
    def ldaConfigurations(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/confs", Param("version", version))
    def saveEdits(implicit version: LDAVersionOpt) = ServiceRoute(POST, "/internal/cortex/lda/saveEdits", Param("version", version))
    def userUriInterest(userId: Id[User], uriId: Id[NormalizedURI])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userUriInterest", Param("userId", userId), Param("uriId", uriId), Param("version", version))
    def userTopicMean(userId: Id[User])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userTopicMean", Param("userId", userId), Param("version", version))
    def sampleURIsForTopic(topicId: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/sampleURIs", Param("topicId", topicId), Param("version", version))
    def getSimilarUsers(userId: Id[User], topK: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/getSimilarUsers", Param("userId", userId), Param("topK", topK), Param("version", version))
    def unamedTopics(limit: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/unamedTopics", Param("limit", limit), Param("version", version))
    def libraryTopic(libId: Id[Library])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/libraryTopic", Param("libId", libId), Param("version", version))
    def userLibraryScore(userId: Id[User], libId: Id[Library])(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/userLibraryScore", Param("userId", userId), Param("libId", libId), Param("version", version))
    def similarLibraries(libId: Id[Library], limit: Int)(implicit version: LDAVersionOpt) = ServiceRoute(GET, "/internal/cortex/lda/similarLibraries", Param("libId", libId), Param("limit", limit), Param("version", version))

    def getSparseLDAFeaturesChanged(modelVersion: ModelVersion[DenseLDA], seqNum: SequenceNumber[NormalizedURI], fetchSize: Int) = ServiceRoute(GET, "/internal/cortex/data/sparseLDAFeaturesChanged", Param("modelVersion", modelVersion), Param("seqNum", seqNum), Param("fetchSize", fetchSize))
  }
}

object Graph extends Service {
  object internal {
    def getGraphStatistics() = ServiceRoute(GET, "/internal/graph/statistics")
    def getGraphUpdaterState() = ServiceRoute(GET, "/internal/graph/state")
    def getGraphKinds() = ServiceRoute(GET, "/internal/graph/kinds")
    def wander() = ServiceRoute(POST, "/internal/graph/wander")
    def getUriAndScores(userId: Id[User], avoidFirstDegreeConnections: Boolean) = ServiceRoute(GET, "/internal/graph/getUriAndScorePairs", Param("userId", userId), Param("avoidFirstDegreeConnections", avoidFirstDegreeConnections))
    def getUserAndScores(userId: Id[User], avoidFirstDegreeConnections: Boolean) = ServiceRoute(GET, "/internal/graph/getUserAndScorePairs", Param("userId", userId), Param("avoidFirstDegreeConnections", avoidFirstDegreeConnections))
    def getSociallyRelatedEntitiesForUser(userId: Id[User]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedEntitiesForUser", Param("userId", userId))
    def getSociallyRelatedEntitiesForOrg(orgId: Id[Organization]) = ServiceRoute(GET, "/internal/graph/getSociallyRelatedEntitiesForOrg", Param("orgId", orgId))
    def explainFeed() = ServiceRoute(POST, "/internal/graph/explainFeed")
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
    def getOrElseComputeRecentContentSignature() = ServiceRoute(POST, "/internal/rover/getOrElseComputeRecentContentSignature")

    def getPornDetectorModel() = ServiceRoute(GET, s"/internal/rover/pornDetector/getModel")
    def detectPorn() = ServiceRoute(POST, s"/internal/rover/pornDetector/detect")
    def whitelist() = ServiceRoute(POST, s"/internal/rover/pornDetector/whitelist")

    def getAllProxies() = ServiceRoute(GET, s"/internal/rover/getAllProxies")
    def saveProxy() = ServiceRoute(POST, s"/internal/rover/saveProxy")
    def getAllUrlRules() = ServiceRoute(GET, s"/internal/rover/getAllUrlRules")
    def saveUrlRule() = ServiceRoute(POST, s"/internal/rover/saveUrlRule")
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

