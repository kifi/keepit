package com.keepit.shoebox

import com.google.inject.Inject
import com.keepit.classify.{ DomainInfo, NormalizedHostname }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.core._
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.{ KeyFormat, TraversableFormat, TupleFormat }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.{ ElectronicMail, EmailAddress }
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType }
import com.keepit.common.store.ImageSize
import com.keepit.common.usersegment.{ UserSegment, UserSegmentCache, UserSegmentFactory, UserSegmentKey }
import com.keepit.common.zookeeper._
import com.keepit.discussion.{ CrossServiceMessage, DiscussionKeep }
import com.keepit.model.KeepEventData.ModifyRecipients
import com.keepit.model._
import com.keepit.model.cache.{ UserSessionViewExternalIdCache, UserSessionViewExternalIdKey }
import com.keepit.model.view.{ LibraryMembershipView, UserSessionView }
import com.keepit.rover.model.BasicImages
import com.keepit.search.{ ActiveExperimentsCache, ActiveExperimentsKey, SearchConfigExperiment }
import com.keepit.shoebox.ShoeboxServiceClient.{ PersistModifyRecipients, RegisterMessageOnKeep, InternKeep, GetPersonalKeepRecipientsOnUris, GetSlackTeamInfo }
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.shoebox.model.{ IngestableUserIpAddress, KeepImagesCache, KeepImagesKey }
import com.keepit.slack.models._
import com.keepit.social._
import org.joda.time.DateTime
import play.api.libs.json.Json._
import play.api.libs.json._
import securesocial.core.IdentityId
import com.keepit.shoebox.ShoeboxServiceClient._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }
import scala.util.Try

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUserIdByIdentityId(identityId: IdentityId): Future[Option[Id[User]]]
  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getUser(userId: Id[User]): Future[Option[User]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(extIds: Set[ExternalId[User]]): Future[Map[ExternalId[User], Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User], BasicUser]]
  def getRecipientsOnKeep(keepId: Id[Keep]): Future[(Map[Id[User], BasicUser], Map[Id[Library], BasicLibrary], Set[EmailAddress])]
  def getCrossServiceKeepsByIds(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], CrossServiceKeep]]
  def getDiscussionKeepsByIds(viewerId: Id[User], keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], DiscussionKeep]]
  def getEmailAddressesForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]]
  def getEmailAddressForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(url: String, contentWanted: Boolean = false): Future[NormalizedURI]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]]
  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getIndexableUrisWithContent(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]]
  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]]
  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def getUserExperiments(userId: Id[User]): Future[Seq[UserExperimentType]]
  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[UserExperimentType]]]
  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]]
  def getUsersByExperiment(experimentType: UserExperimentType): Future[Set[User]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]]
  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]]
  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]]
  def getFriends(userId: Id[User]): Future[Set[Id[User]]]
  def logEvent(userId: Id[User], event: JsObject): Unit
  def createDeepLink(initiator: Option[Id[User]], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator): Unit
  def getDeepUrl(locator: DeepLocator, recipient: Id[User]): Future[String]
  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]]
  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]]
  def getFriendRequestsRecipientIdBySender(senderId: Id[User]): Future[Seq[Id[User]]]
  def getUserValue(userId: Id[User], key: UserValueName): Future[Option[String]]
  def setUserValue(userId: Id[User], key: UserValueName, value: String): Unit
  def getUserSegment(userId: Id[User]): Future[UserSegment]
  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String]
  def triggerRawKeepImport(): Unit
  def triggerSocialGraphFetch(id: Id[SocialUserInfo]): Future[Unit]
  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int): Future[Seq[UserConnection]]
  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]]
  def getUserImageUrl(userId: Id[User], width: Int): Future[String]
  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String]
  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]]
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]]
  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]]
  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[KeepAndTags]]
  def getCrossServiceKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[CrossServiceKeepAndTags]]
  def getAllFakeUsers(): Future[Set[Id[User]]]
  def getInvitations(senderId: Id[User]): Future[Seq[Invitation]]
  def getSocialConnections(userId: Id[User]): Future[Seq[SocialUserBasicInfo]]
  def addInteractions(userId: Id[User], actions: Seq[(Either[Id[User], EmailAddress], String)]): Unit
  def processAndSendMail(email: EmailToSend): Future[Boolean]
  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryView]]
  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[DetailedLibraryView]]
  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int): Future[Seq[LibraryMembershipView]]
  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String]): Future[Boolean]
  def getPersonalKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[PersonalKeep]]]
  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Future[Map[Id[Library], BasicLibraryDetails]]
  def getLibraryCardInfos(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Future[Map[Id[Library], LibraryCardInfo]]
  def getKeepCounts(userIds: Set[Id[User]]): Future[Map[Id[User], Int]]
  def getKeepImages(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], BasicImages]]
  def getLibrariesWithWriteAccess(userId: Id[User]): Future[Set[Id[Library]]]
  def getLibraryURIs(libId: Id[Library]): Future[Seq[Id[NormalizedURI]]]
  def getIngestableOrganizations(seqNum: SequenceNumber[Organization], fetchSize: Int): Future[Seq[IngestableOrganization]]
  def getIngestableOrganizationMemberships(seqNum: SequenceNumber[OrganizationMembership], fetchSize: Int): Future[Seq[IngestableOrganizationMembership]]
  def getIngestableUserIpAddresses(seqNum: SequenceNumber[IngestableUserIpAddress], fetchSize: Int): Future[Seq[IngestableUserIpAddress]]
  def getIngestableOrganizationMembershipCandidates(seqNum: SequenceNumber[OrganizationMembershipCandidate], fetchSize: Int): Future[Seq[IngestableOrganizationMembershipCandidate]]
  def internDomainsByDomainNames(domainNames: Set[NormalizedHostname]): Future[Map[NormalizedHostname, DomainInfo]]
  def getOrganizationMembers(orgId: Id[Organization]): Future[Set[Id[User]]]
  def getOrganizationInviteViews(orgId: Id[Organization]): Future[Set[OrganizationInviteView]]
  def hasOrganizationMembership(orgId: Id[Organization], userId: Id[User]): Future[Boolean]
  def getIngestableOrganizationDomainOwnerships(seqNum: SequenceNumber[OrganizationDomainOwnership], fetchSize: Int): Future[Seq[IngestableOrganizationDomainOwnership]]
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]]
  def getOrganizationsForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Set[Id[Organization]]]]
  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues]
  def getBasicOrganizationsByIds(ids: Set[Id[Organization]]): Future[Map[Id[Organization], BasicOrganization]]
  def getOrganizationUserRelationship(orgId: Id[Organization], userId: Id[User]): Future[OrganizationUserRelationship]
  def getLibraryMembershipView(libraryId: Id[Library], userId: Id[User]): Future[Option[LibraryMembershipView]]
  def getUserPermissionsByOrgId(orgIds: Set[Id[Organization]], userId: Id[User]): Future[Map[Id[Organization], Set[OrganizationPermission]]]
  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): Future[SlackChannelIntegrations]
  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], SourceAttribution]]
  def getRelevantKeepsByUserAndUri(userId: Id[User], uriId: Id[NormalizedURI], before: Option[DateTime], limit: Int): Future[Seq[Id[Keep]]]
  def getPersonalKeepRecipientsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess] = None): Future[Map[Id[NormalizedURI], Set[CrossServiceKeepRecipients]]]
  def getSlackTeamIds(orgIds: Set[Id[Organization]]): Future[Map[Id[Organization], SlackTeamId]]
  def getSlackTeamInfo(slackTeamId: SlackTeamId): Future[Option[InternalSlackTeamInfo]]

  // TODO[keepscussions]: kill these methods once Eliza endpoints are deprecated
  def internKeep(creator: Id[User], users: Set[Id[User]], emails: Set[EmailAddress], uriId: Id[NormalizedURI], url: String, title: Option[String], note: Option[String], source: Option[KeepSource]): Future[CrossServiceKeep]
  def editRecipientsOnKeep(editorId: Id[User], keepId: Id[Keep], diff: KeepRecipientsDiff): Future[Unit]
  def persistModifyRecipients(keepId: Id[Keep], eventData: ModifyRecipients, source: Option[KeepEventSource]): Future[Option[CommonAndBasicKeepEvent]]
  def registerMessageOnKeep(keepId: Id[Keep], msg: CrossServiceMessage): Future[Unit]
}

case class ShoeboxCacheProvider @Inject() (
  userExternalIdCache: UserExternalIdCache,
  uriIdCache: NormalizedURICache,
  bookmarkUriUserCache: KeepUriUserCache,
  basicUserCache: BasicUserUserIdCache,
  activeSearchConfigExperimentsCache: ActiveExperimentsCache,
  userExperimentCache: UserExperimentCache,
  externalUserIdCache: ExternalUserIdCache,
  userIdCache: UserIdCache,
  socialUserNetworkCache: SocialUserInfoNetworkCache,
  socialUserCache: SocialUserInfoUserCache,
  identityUserIdCache: IdentityUserIdCache,
  userSessionExternalIdCache: UserSessionViewExternalIdCache,
  userConnectionsCache: UserConnectionIdCache,
  searchFriendsCache: SearchFriendsCache,
  userValueCache: UserValueCache,
  userConnCountCache: UserConnectionCountCache,
  userBookmarkCountCache: KeepCountCache,
  userSegmentCache: UserSegmentCache,
  extensionVersionCache: ExtensionVersionInstallationIdCache,
  allFakeUsersCache: AllFakeUsersCache,
  librariesWithWriteAccessCache: LibrariesWithWriteAccessCache,
  keepImagesCache: KeepImagesCache,
  primaryOrgForUserCache: PrimaryOrgForUserCache,
  organizationMembersCache: OrganizationMembersCache,
  basicOrganizationIdCache: BasicOrganizationIdCache,
  slackIntegrationsCache: SlackChannelIntegrationsCache,
  slackTeamIdByOrganizationIdCache: SlackTeamIdOrgIdCache,
  sourceAttributionByKeepIdCache: SourceAttributionKeepIdCache)

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  cacheProvider: ShoeboxCacheProvider,
  implicit val executionContext: ScalaExecutionContext)
    extends ShoeboxServiceClient with Logging {

  val MaxUrlLength = 3000
  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))
  val extraLongTimeout = CallTimeouts(responseTimeout = Some(60000), maxWaitTime = Some(60000), maxJsonParseTime = Some(30000))
  val superExtraLongTimeoutJustForEmbedly = CallTimeouts(responseTimeout = Some(250000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  // request consolidation
  private[this] val consolidateGetUserReq = new RequestConsolidator[Id[User], Option[User]](ttl = 30 seconds)
  private[this] val consolidateSearchFriendsReq = new RequestConsolidator[SearchFriendsKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateUserConnectionsReq = new RequestConsolidator[UserConnectionIdKey, Set[Id[User]]](ttl = 3 seconds)

  private def redundantDBConnectionCheck(request: Iterable[_]) {
    if (request.isEmpty) {
      airbrakeNotifier.notify("ShoeboxServiceClient: trying to call DB with empty list.")
    }
  }

  def getUserIdByIdentityId(identityId: IdentityId): Future[Option[Id[User]]] = {
    cacheProvider.identityUserIdCache.getOrElseFutureOpt(IdentityUserIdKey(identityId)) {
      call(Shoebox.internal.getUserIdByIdentityId(providerId = identityId.providerId, id = identityId.userId)).map { r =>
        r.json.asOpt[Id[User]]
      }
    }
  }

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    cacheProvider.userExternalIdCache.getOrElseFutureOpt(UserExternalIdKey(id)) {
      call(Shoebox.internal.getUserOpt(id)).map { r =>
        r.json match {
          case JsNull => None
          case js: JsValue => Some(js.as[User])
        }
      }
    }
  }

  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]] = {
    cacheProvider.socialUserCache.get(SocialUserInfoUserKey(userId)) match {
      case Some(sui) => Future.successful(sui)
      case None => call(Shoebox.internal.getSocialUserInfosByUserId(userId)) map { resp =>
        resp.json.as[Seq[SocialUserInfo]]
      }
    }
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarksChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[Keep]]
    }
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]] = {
    cacheProvider.bookmarkUriUserCache.getOrElseFutureOpt(KeepUriUserKey(uriId, userId)) {
      call(Shoebox.internal.getBookmarkByUriAndUser(uriId, userId)).map { r =>
        r.json.asOpt[Keep]
      }
    }
  }

  def sendMail(email: ElectronicMail): Future[Boolean] = {
    call(Shoebox.internal.sendMail(), Json.toJson(email)).map(r => r.body.toBoolean)
  }

  def processAndSendMail(email: EmailToSend) = {
    call(Shoebox.internal.processAndSendMail(), Json.toJson(email)).map(r => r.body.toBoolean)
  }

  def getUser(userId: Id[User]): Future[Option[User]] = consolidateGetUserReq(userId) { key =>
    val user = cacheProvider.userIdCache.get(UserIdKey(key))
    if (user.isDefined) {
      Future.successful(user)
    } else {
      call(Shoebox.internal.getUsers(key.toString)).map { r =>
        Json.fromJson[Seq[User]](r.json).get.headOption
      }
    }
  }

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    redundantDBConnectionCheck(userIds)
    val query = userIds.mkString(",")
    call(Shoebox.internal.getUsers(query)).map { r =>
      Json.fromJson[Seq[User]](r.json).get
    }
  }

  def getUserIdsByExternalIds(extIds: Set[ExternalId[User]]): Future[Map[ExternalId[User], Id[User]]] = {
    implicit val extIdToIdMapFormat = TraversableFormat.mapFormat[ExternalId[User], Id[User]](_.id, s => Try(ExternalId[User](s)).toOption)
    cacheProvider.externalUserIdCache.bulkGetOrElseFuture(extIds.map(ExternalUserIdKey)) { missingKeys =>
      val payload = Json.toJson(missingKeys.map(_.id))
      call(Shoebox.internal.getUserIdsByExternalIds(), payload).map { res =>
        val missing = res.json.as[Map[ExternalId[User], Id[User]]]
        missing.map { case (extId, id) => ExternalUserIdKey(extId) -> id }
      }
    }.map { bigMap => bigMap.map { case (key, value) => key.id -> value } }
  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    val uniqueUserIds = userIds.toSet
    Future {
      cacheProvider.basicUserCache.bulkGet(uniqueUserIds.map(BasicUserUserIdKey(_))).collect {
        case (BasicUserUserIdKey(userId), Some(basicUser)) => userId -> basicUser
      }
    } flatMap { cached =>
      val missingUserIds = uniqueUserIds -- cached.keySet
      if (missingUserIds.isEmpty) Future.successful(cached)
      else {
        val payload = Json.toJson(missingUserIds)
        call(Shoebox.internal.getBasicUsers(), payload).map { res =>
          implicit val tupleReads = TupleFormat.tuple2Reads[Id[User], BasicUser]
          val missing = res.json.as[Seq[(Id[User], BasicUser)]].toMap
          cached ++ missing
        }
      }
    }
  }

  def getRecipientsOnKeep(keepId: Id[Keep]): Future[(Map[Id[User], BasicUser], Map[Id[Library], BasicLibrary], Set[EmailAddress])] = {
    import GetRecipientsOnKeep._
    call(Shoebox.internal.getRecipientsOnKeep(keepId)).map { res =>
      val Response(users, libraries, emails) = res.json.as[Response]
      (users, libraries, emails)
    }
  }

  def getCrossServiceKeepsByIds(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], CrossServiceKeep]] = {
    val payload = Json.toJson(keepIds)
    call(Shoebox.internal.getCrossServiceKeepsByIds, payload).map { res =>
      res.json.as[Map[Id[Keep], CrossServiceKeep]]
    }
  }
  def getDiscussionKeepsByIds(viewerId: Id[User], keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], DiscussionKeep]] = {
    implicit val payloadFormat = KeyFormat.key2Format[Id[User], Set[Id[Keep]]]("viewerId", "keepIds")
    val payload = Json.toJson(viewerId, keepIds)
    call(Shoebox.internal.getDiscussionKeepsByIds, payload).map { res =>
      res.json.as[Map[Id[Keep], DiscussionKeep]]
    }
  }

  def getEmailAddressesForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getEmailAddressesForUsers(), payload, callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { res =>
      log.debug(s"[res.request.trackingId] getEmailAddressesForUsers for users $userIds returns json ${res.json}")
      res.json.as[Map[String, Seq[EmailAddress]]].map { case (id, emails) => Id[User](id.toLong) -> emails }
    }
  }

  def getEmailAddressForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getEmailAddressForUsers(), payload) map { _.json.as[Map[Id[User], Option[EmailAddress]]] }
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateSearchFriendsReq(SearchFriendsKey(userId)) { key =>
    cacheProvider.searchFriendsCache.get(key) match {
      case Some(friends) => Future.successful(friends)
      case None =>
        call(Shoebox.internal.getSearchFriends(userId)).map { r =>
          r.json.as[Set[Id[User]]]
        }
    }
  }

  def getFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateUserConnectionsReq(UserConnectionIdKey(userId)) { key =>
    cacheProvider.userConnectionsCache.get(key) match {
      case Some(friends) => Future.successful(friends)
      case None =>
        call(Shoebox.internal.getConnectedUsers(userId)).map { r =>
          r.json.as[Set[Id[User]]]
        }
    }
  }

  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]] = {
    call(Shoebox.internal.getUnfriends(userId)).map { r =>
      r.json.as[Set[Id[User]]]
    }
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI] = {
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(uriId)) {
      call(Shoebox.internal.getNormalizedURI(uriId)).map(r => Json.fromJson[NormalizedURI](r.json).get)
    }
  }

  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]] =
    call(Shoebox.internal.getNormalizedURIByURL(), JsString(url.take(MaxUrlLength)), callTimeouts = CallTimeouts(maxWaitTime = Some(400))).map { r =>
      r.json match {
        case JsNull => None
        case js: JsValue => Some(js.as[NormalizedURI])
        case null => None
      }
    }

  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]] =
    call(Shoebox.internal.getNormalizedUriByUrlOrPrenormalize(), JsString(url.take(MaxUrlLength))).map { r =>
      (r.json \ "normalizedURI").asOpt[NormalizedURI].map(Left(_)) getOrElse Right((r.json \ "url").as[String])
    }

  def internNormalizedURI(url: String, contentWanted: Boolean): Future[NormalizedURI] = {
    val payload = Json.obj("url" -> url, "contentWanted" -> contentWanted)
    call(Shoebox.internal.internNormalizedURI, payload).map(r =>
      r.json.as[NormalizedURI]
    )
  }

  def persistServerSearchEvent(metaData: JsObject): Unit = {
    call(Shoebox.internal.persistServerSearchEvent, metaData)
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[Phrase]]
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    cacheProvider.activeSearchConfigExperimentsCache.getOrElseFuture(ActiveExperimentsKey) {
      call(Shoebox.internal.getActiveExperiments).map { r =>
        r.json.as[Seq[SearchConfigExperiment]]
      }
    }
  }
  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    call(Shoebox.internal.getExperiments).map { r =>
      r.json.as[Seq[SearchConfigExperiment]]
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.getExperiment(id)).map { r =>
      r.json.as[SearchConfigExperiment]
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.saveExperiment, Json.toJson(experiment)).map { r =>
      r.json.as[SearchConfigExperiment]
    }
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[UserExperimentType]] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
      case Some(states) => Future.successful(states)
      case None => call(Shoebox.internal.getUserExperiments(userId)).map { r =>
        r.json.as[Seq[UserExperimentType]]
      }
    }
  }

  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[UserExperimentType]]] = {
    redundantDBConnectionCheck(userIds)
    implicit val idFormat = Id.format[User]
    val payload = JsArray(userIds.map { x => Json.toJson(x) })
    call(Shoebox.internal.getExperimentsByUserIds(), payload).map { res =>
      res.json.as[Map[String, Set[UserExperimentType]]]
        .map { case (id, exps) => Id[User](id.toLong) -> exps }.toMap
    }
  }

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = {
    call(Shoebox.internal.getExperimentGenerators()).map { res =>
      res.json.as[Seq[ProbabilisticExperimentGenerator]]
    }
  }

  def getUsersByExperiment(experimentType: UserExperimentType): Future[Set[User]] = {
    call(Shoebox.internal.getUsersByExperiment(experimentType)).map(_.json.as[Set[User]])
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getIndexableUris(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[IndexableUri]]
    }
  }

  def getIndexableUrisWithContent(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getIndexableUrisWithContent(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[IndexableUri]]
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]] = {
    call(Shoebox.internal.getUserIndexable(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[User]]
    }
  }

  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]] = {
    call(Shoebox.internal.getHighestUriSeq(), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[SequenceNumber[NormalizedURI]]
    }
  }

  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]] = {
    cacheProvider.userSessionExternalIdCache.get(UserSessionViewExternalIdKey(sessionId)) match {
      case Some(session) => Future.successful(Some(session))
      case None =>
        call(Shoebox.internal.getSessionByExternalId(sessionId)).map { r =>
          r.json match {
            case jso: JsObject => Json.fromJson[UserSessionView](jso).asOpt
            case _ => None
          }
        }
    }
  }

  def logEvent(userId: Id[User], event: JsObject): Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "event" -> event)
    call(Shoebox.internal.logEvent, payload)
  }

  def createDeepLink(initiator: Option[Id[User]], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator): Unit = {
    implicit val userFormatter = Id.format[User]
    implicit val uriFormatter = Id.format[NormalizedURI]
    val payload = Json.obj(
      "initiator" -> initiator,
      "recipient" -> recipient,
      "uriId" -> uriId,
      "locator" -> locator.value
    )
    call(Shoebox.internal.createDeepLink, payload)
  }

  def getDeepUrl(locator: DeepLocator, recipient: Id[User]): Future[String] = {
    val payload = Json.obj(
      "locator" -> locator.value,
      "recipient" -> recipient
    )
    call(Shoebox.internal.getDeepUrl, payload).map { r =>
      r.json.as[String]
    }
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = {
    call(Shoebox.internal.getNormalizedUriUpdates(lowSeq, highSeq), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      var m = Vector.empty[(Id[NormalizedURI], NormalizedURI)]
      r.json match {
        case jso: JsValue => {
          val rv = jso.as[JsArray].value.foreach { js =>
            val id = Id[NormalizedURI]((js \ "id").as[Long])
            val uri = Json.fromJson[NormalizedURI](js \ "uri").get
            m = m :+ (id, uri)
          }
          m
        }
        case _ => m
      }
    }
  }

  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]] = {
    val payload = Json.toJson(uriIds)
    call(Shoebox.internal.getHelpRankInfo, payload, callTimeouts = longTimeout) map { r =>
      r.json.as[Seq[HelpRankInfo]]
    }
  }

  def getFriendRequestsRecipientIdBySender(senderId: Id[User]): Future[Seq[Id[User]]] = {
    call(Shoebox.internal.getFriendRequestRecipientIdBySender(senderId)).map { r =>
      r.json.as[JsArray].value.map { x => Json.fromJson[Id[User]](x).get }
    }
  }

  def getUserValue(userId: Id[User], key: UserValueName): Future[Option[String]] = {
    cacheProvider.userValueCache.getOrElseFutureOpt(UserValueKey(userId, key)) {
      call(Shoebox.internal.getUserValue(userId, key)).map(_.json.asOpt[String])
    }
  }

  def setUserValue(userId: Id[User], key: UserValueName, value: String): Unit = { call(Shoebox.internal.setUserValue(userId, key), JsString(value)) }

  def getUserSegment(userId: Id[User]): Future[UserSegment] = {
    cacheProvider.userSegmentCache.getOrElseFuture(UserSegmentKey(userId)) {
      val friendsCount = cacheProvider.userConnCountCache.get(UserConnectionCountKey(userId))
      val bmsCount = cacheProvider.userBookmarkCountCache.get(KeepCountKey(userId))

      (friendsCount, bmsCount) match {
        case (Some(f), Some(bm)) =>
          val segment = UserSegmentFactory(bm, f)
          Future.successful(segment)
        case _ =>
          call(Shoebox.internal.getUserSegment(userId)).map { x => Json.fromJson[UserSegment](x.json).get }
      }
    }
  }

  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String] = {
    cacheProvider.extensionVersionCache.getOrElseFuture(ExtensionVersionInstallationIdKey(installationId)) {
      call(Shoebox.internal.getExtensionVersion(installationId)).map(_.json.as[String])
    }
  }

  def triggerRawKeepImport(): Unit = {
    call(Shoebox.internal.triggerRawKeepImport(), callTimeouts = extraLongTimeout, routingStrategy = leaderPriority)
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]): Future[Unit] = {
    call(call = Shoebox.internal.triggerSocialGraphFetch(socialUserInfoId), callTimeouts = extraLongTimeout, routingStrategy = leaderPriority).map(_ => ())(ExecutionContext.immediate)
  }

  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int): Future[Seq[UserConnection]] = {
    call(Shoebox.internal.getUserConnectionsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[UserConnection]](r.json).get
    }
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]] = {
    call(Shoebox.internal.getSearchFriendsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[SearchFriend]](r.json).get
    }
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = {
    call(Shoebox.internal.getUserImageUrl(userId, width)).map { r =>
      r.json.as[String]
    }
  }

  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String] = {
    call(Shoebox.internal.getUnsubscribeUrlForEmail(email)).map { r =>
      r.body
    }
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]] = {
    call(Shoebox.internal.getIndexableSocialConnections(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[IndexableSocialConnection]]
    }
  }
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]] = {
    call(Shoebox.internal.getIndexableSocialUserInfos(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[SocialUserInfo]]
    }
  }

  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]] = {
    call(Shoebox.internal.getEmailAccountUpdates(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[EmailAccountUpdate]]
    }
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[KeepAndTags]] = {
    call(Shoebox.internal.getKeepsAndTagsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[KeepAndTags]]
    }
  }

  def getCrossServiceKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[CrossServiceKeepAndTags]] = {
    call(Shoebox.internal.getCrossServiceKeepsAndTagsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[CrossServiceKeepAndTags]]
    }
  }

  def getAllFakeUsers(): Future[Set[Id[User]]] = cacheProvider.allFakeUsersCache.getOrElseFuture(AllFakeUsersKey) {
    call(Shoebox.internal.getAllFakeUsers()).map(_.json.as[Set[Id[User]]])
  }

  def getInvitations(senderId: Id[User]): Future[Seq[Invitation]] = {
    call(Shoebox.internal.getInvitations(senderId)).map(_.json.as[Seq[Invitation]])
  }

  def getSocialConnections(userId: Id[User]): Future[Seq[SocialUserBasicInfo]] = {
    call(Shoebox.internal.getSocialConnections(userId)).map(_.json.as[Seq[SocialUserBasicInfo]])
  }

  def addInteractions(userId: Id[User], actions: Seq[(Either[Id[User], EmailAddress], String)]): Unit = {
    val jsonActions = actions.collect {
      case (Left(id), action) => Json.obj("user" -> id, "action" -> action)
      case (Right(email), action) => Json.obj("email" -> email, "action" -> action)
    }
    call(Shoebox.internal.addInteractions(userId), body = Json.toJson(jsonActions))
  }

  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryView]] = {
    call(Shoebox.internal.getLibrariesChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r => (r.json).as[Seq[LibraryView]] }
  }

  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[DetailedLibraryView]] = {
    call(Shoebox.internal.getDetailedLibrariesChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r => (r.json).as[Seq[DetailedLibraryView]] }
  }

  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int): Future[Seq[LibraryMembershipView]] = {
    call(Shoebox.internal.getLibraryMembershipsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r => (r.json).as[Seq[LibraryMembershipView]] }
  }

  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String]): Future[Boolean] = {
    val body = Json.obj(
      "libraryId" -> libraryId,
      "userId" -> userId,
      "authToken" -> authToken)
    call(Shoebox.internal.canViewLibrary, body = body).map(_.json.as[Boolean])
  }

  def getPersonalKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[PersonalKeep]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty[Id[NormalizedURI], Set[PersonalKeep]]) else {
      call(Shoebox.internal.getPersonalKeeps(userId), Json.toJson(uriIds), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[PersonalKeep]]
        r.json.as[Seq[(Id[NormalizedURI], Set[PersonalKeep])]].toMap
      }
    }
  }

  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Future[Map[Id[Library], BasicLibraryDetails]] = {
    if (libraryIds.isEmpty) Future.successful(Map.empty[Id[Library], BasicLibraryDetails]) else {
      val payload = Json.obj(
        "libraryIds" -> libraryIds,
        "idealImageSize" -> idealImageSize,
        "viewerId" -> viewerId
      )
      call(Shoebox.internal.getBasicLibraryDetails, payload).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[Library], BasicLibraryDetails]
        r.json.as[Seq[(Id[Library], BasicLibraryDetails)]].toMap
      }
    }
  }
  def getLibraryCardInfos(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Future[Map[Id[Library], LibraryCardInfo]] = {
    if (libraryIds.isEmpty) Future.successful(Map.empty[Id[Library], LibraryCardInfo]) else {
      val payload = Json.obj(
        "libraryIds" -> libraryIds,
        "idealImageSize" -> idealImageSize,
        "viewerId" -> viewerId
      )
      call(Shoebox.internal.getLibraryCardInfos, payload, callTimeouts = longTimeout).map { r =>
        implicit val libraryCardInfoReads = LibraryCardInfo.internalReads
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[Library], LibraryCardInfo]
        r.json.as[Seq[(Id[Library], LibraryCardInfo)]].toMap
      }
    }
  }

  def getKeepCounts(userIds: Set[Id[User]]): Future[Map[Id[User], Int]] = {
    if (userIds.isEmpty) Future.successful(Map.empty[Id[User], Int]) else {
      call(Shoebox.internal.getKeepCounts, Json.toJson(userIds)).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[User], Int]
        r.json.as[Seq[(Id[User], Int)]].toMap.withDefaultValue(0)
      }
    }
  }

  def getKeepImages(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], BasicImages]] = {
    if (keepIds.isEmpty) Future.successful(Map.empty)
    else {
      import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
      val keys = keepIds.map(KeepImagesKey(_))
      cacheProvider.keepImagesCache.bulkGetOrElseFutureOpt(keys) { missingKeys =>
        val missingKeepIds = missingKeys.map(_.keepId)
        val payload = Json.toJson(missingKeepIds)
        call(Shoebox.internal.getKeepImages, payload).map { r =>
          implicit val reads = TupleFormat.tuple2Reads[Id[Keep], BasicImages]
          val missingImagesByKeepId = (r.json).as[Seq[(Id[Keep], BasicImages)]].toMap
          missingKeys.map { key => key -> missingImagesByKeepId.get(key.keepId) }.toMap
        }
      } imap {
        _.collect { case (key, Some(images)) => key.keepId -> images }
      }
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User]): Future[Set[Id[Library]]] = {
    cacheProvider.librariesWithWriteAccessCache.get(LibrariesWithWriteAccessUserKey(userId)) match {
      case Some(cachedLibraryIds) => Future.successful(cachedLibraryIds)
      case None => call(Shoebox.internal.getLibrariesWithWriteAccess(userId)).map(_.json.as[Set[Id[Library]]])
    }
  }

  def getLibraryURIs(libId: Id[Library]): Future[Seq[Id[NormalizedURI]]] = {
    call(Shoebox.internal.getLibraryURIS(libId), callTimeouts = longTimeout).map { _.json.as[Seq[Id[NormalizedURI]]] }
  }

  def getIngestableOrganizations(seqNum: SequenceNumber[Organization], fetchSize: Int): Future[Seq[IngestableOrganization]] = {
    call(Shoebox.internal.getIngestableOrganizations(seqNum, fetchSize), routingStrategy = offlinePriority).map { _.json.as[Seq[IngestableOrganization]] }
  }

  def getIngestableOrganizationMemberships(seqNum: SequenceNumber[OrganizationMembership], fetchSize: Int): Future[Seq[IngestableOrganizationMembership]] = {
    call(Shoebox.internal.getIngestableOrganizationMemberships(seqNum, fetchSize), routingStrategy = offlinePriority).map { _.json.as[Seq[IngestableOrganizationMembership]] }
  }

  def getIngestableOrganizationMembershipCandidates(seqNum: SequenceNumber[OrganizationMembershipCandidate], fetchSize: Int): Future[Seq[IngestableOrganizationMembershipCandidate]] = {
    call(Shoebox.internal.getIngestableOrganizationMembershipCandidates(seqNum, fetchSize), routingStrategy = offlinePriority).map { _.json.as[Seq[IngestableOrganizationMembershipCandidate]] }
  }

  def getIngestableUserIpAddresses(seqNum: SequenceNumber[IngestableUserIpAddress], fetchSize: Int): Future[Seq[IngestableUserIpAddress]] = {
    call(Shoebox.internal.getIngestableUserIpAddresses(seqNum, fetchSize), routingStrategy = offlinePriority).map { _.json.as[Seq[IngestableUserIpAddress]] }
  }

  def internDomainsByDomainNames(domainNames: Set[NormalizedHostname]): Future[Map[NormalizedHostname, DomainInfo]] = {
    if (domainNames.isEmpty) Future.successful(Map.empty)
    else {
      val payload = Json.obj("domainNames" -> domainNames)
      call(Shoebox.internal.internDomainsByDomainNames(), payload, routingStrategy = offlinePriority).map {
        _.json.as[Map[String, DomainInfo]].map { case (hostname: String, domainInfo: DomainInfo) => NormalizedHostname(hostname) -> domainInfo }
      }
    }
  }

  def getOrganizationMembers(orgId: Id[Organization]): Future[Set[Id[User]]] = {
    cacheProvider.organizationMembersCache.getOrElseFuture(OrganizationMembersKey(orgId)) {
      call(Shoebox.internal.getOrganizationMembers(orgId)).map(_.json.as[Set[Id[User]]])
    }
  }

  def hasOrganizationMembership(orgId: Id[Organization], userId: Id[User]): Future[Boolean] = {
    call(Shoebox.internal.hasOrganizationMembership(orgId, userId)).map(_.json.as[Boolean])
  }

  def getOrganizationInviteViews(orgId: Id[Organization]): Future[Set[OrganizationInviteView]] = {
    call(Shoebox.internal.getOrganizationInviteViews(orgId)).map(_.json.as[Set[OrganizationInviteView]])
  }

  def getIngestableOrganizationDomainOwnerships(seqNum: SequenceNumber[OrganizationDomainOwnership], fetchSize: Int): Future[Seq[IngestableOrganizationDomainOwnership]] = {
    call(Shoebox.internal.getIngestableOrganizationDomainOwnerships(seqNum, fetchSize), routingStrategy = offlinePriority).map { _.json.as[Seq[IngestableOrganizationDomainOwnership]] }
  }

  def getPrimaryOrg(id: Id[User]): Future[Option[Id[Organization]]] = {
    cacheProvider.primaryOrgForUserCache.getOrElseFutureOpt(PrimaryOrgForUserKey(id)) {
      call(Shoebox.internal.getPrimaryOrg(id)).map { r =>
        r.json match {
          case JsNull => None
          case js: JsValue => Some(js.as[Id[Organization]])
        }
      }
    }
  }

  def getOrganizationsForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Set[Id[Organization]]]] = {
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getOrganizationsForUsers(), payload).map { _.json.as[Map[Id[User], Set[Id[Organization]]]] }
  }

  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues] = {
    call(Shoebox.internal.getOrgTrackingValues(orgId)).map { _.json.as[OrgTrackingValues] }
  }

  def getBasicOrganizationsByIds(ids: Set[Id[Organization]]): Future[Map[Id[Organization], BasicOrganization]] = {
    cacheProvider.basicOrganizationIdCache.bulkGetOrElseFuture(ids.map(BasicOrganizationIdKey.apply _)) { missing =>
      val payload = Json.toJson(missing.map(_.id))
      call(Shoebox.internal.getBasicOrganizationsByIds(), payload).map {
        _.json.as[Map[Id[Organization], BasicOrganization]].map {
          case (orgId, org) => (BasicOrganizationIdKey(orgId), org)
        }
      }
    }.map {
      _.map {
        case (orgKey, org) => (orgKey.id, org)
      }
    }
  }

  def getLibraryMembershipView(libraryId: Id[Library], userId: Id[User]): Future[Option[LibraryMembershipView]] = {
    call(Shoebox.internal.getLibraryMembershipView(libraryId, userId)).map { _.json.as[Option[LibraryMembershipView]] }
  }

  def getOrganizationUserRelationship(orgId: Id[Organization], userId: Id[User]): Future[OrganizationUserRelationship] = {
    call(Shoebox.internal.getOrganizationUserRelationship(orgId, userId)).map { _.json.as[OrganizationUserRelationship] }
  }

  def getUserPermissionsByOrgId(orgIds: Set[Id[Organization]], userId: Id[User]): Future[Map[Id[Organization], Set[OrganizationPermission]]] = {
    val payload = Json.obj("orgIds" -> orgIds, "userId" -> userId)
    call(Shoebox.internal.getUserPermissionsByOrgId, payload).map { _.json.as[Map[Id[Organization], Set[OrganizationPermission]]] }
  }

  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): Future[SlackChannelIntegrations] = {
    cacheProvider.slackIntegrationsCache.getOrElseFuture(SlackChannelIntegrationsKey(teamId, channelId)) {
      val payload = Json.obj("teamId" -> teamId, "channelId" -> channelId)
      call(Shoebox.internal.getIntegrationsBySlackChannel, payload).map { _.json.as[SlackChannelIntegrations] }
    }
  }

  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], SourceAttribution]] = {
    cacheProvider.sourceAttributionByKeepIdCache.bulkGetOrElseFuture(keepIds.map(SourceAttributionKeepIdKey(_))) { missingKeys =>
      val payload = Json.obj("keepIds" -> missingKeys.map(_.keepId))
      implicit val reads = SourceAttribution.internalFormat
      call(Shoebox.internal.getSourceAttributionForKeeps, payload).map {
        _.json.as[Map[Id[Keep], SourceAttribution]].map {
          case (keepId, attribution) => SourceAttributionKeepIdKey(keepId) -> attribution
        }
      }
    }.imap(_.map { case (SourceAttributionKeepIdKey(keepId), attribution) => keepId -> attribution })
  }

  def getRelevantKeepsByUserAndUri(userId: Id[User], uriId: Id[NormalizedURI], before: Option[DateTime], limit: Int): Future[Seq[Id[Keep]]] = {
    val payload = Json.obj("userId" -> userId, "uriId" -> uriId, "before" -> before, "limit" -> limit)
    call(Shoebox.internal.getRelevantKeepsByUserAndUri(), payload).map { j =>
      j.json.asOpt[Seq[Id[Keep]]].getOrElse(Seq.empty)
    }
  }

  def getPersonalKeepRecipientsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess]): Future[Map[Id[NormalizedURI], Set[CrossServiceKeepRecipients]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      import GetPersonalKeepRecipientsOnUris._
      val request = Request(userId, uriIds, excludeAccess)
      call(Shoebox.internal.getPersonalKeepRecipientsOnUris(), body = Json.toJson(request)).map(_.json.as[Response].keepRecipientsByUriId)
    }
  }

  def getSlackTeamIds(orgIds: Set[Id[Organization]]): Future[Map[Id[Organization], SlackTeamId]] = {
    cacheProvider.slackTeamIdByOrganizationIdCache.bulkGetOrElseFutureOpt(orgIds.map(SlackTeamIdOrgIdKey(_))) { missingKeys =>
      val payload = Json.obj("orgIds" -> missingKeys.map(_.organizationId))
      call(Shoebox.internal.getSlackTeamIds, payload).map { res =>
        val existing = res.json.as[Map[Id[Organization], SlackTeamId]]
        missingKeys.map(key => key -> existing.get(key.organizationId)).toMap
      }
    }.imap(_.collect { case (SlackTeamIdOrgIdKey(orgId), Some(slackTeamId)) => orgId -> slackTeamId })
  }

  def getSlackTeamInfo(slackTeamId: SlackTeamId): Future[Option[InternalSlackTeamInfo]] = {
    import GetSlackTeamInfo._
    call(Shoebox.internal.getSlackTeamInfo(slackTeamId)).map {
      _.json.asOpt[Response].map {
        case Response(teamInfo) => teamInfo
      }
    }
  }

  def internKeep(creator: Id[User], users: Set[Id[User]], emails: Set[EmailAddress], uriId: Id[NormalizedURI], url: String, title: Option[String], note: Option[String], source: Option[KeepSource]): Future[CrossServiceKeep] = {
    import InternKeep._
    val request = Request(creator, users + creator, emails, uriId, url, title, note, source)
    call(Shoebox.internal.internKeep(), body = Json.toJson(request)).map { response =>
      response.json.as[CrossServiceKeep]
    }
  }

  def editRecipientsOnKeep(editorId: Id[User], keepId: Id[Keep], diff: KeepRecipientsDiff): Future[Unit] = {
    val jsRecipients = Json.toJson(diff)(KeepRecipientsDiff.internalFormat)
    call(Shoebox.internal.editRecipientsOnKeep(editorId, keepId), body = Json.obj("diff" -> jsRecipients)).map(_ => ())
  }

  def registerMessageOnKeep(keepId: Id[Keep], msg: CrossServiceMessage): Future[Unit] = {
    import RegisterMessageOnKeep._
    val request = Request(keepId, msg)
    call(Shoebox.internal.registerMessageOnKeep(), body = Json.toJson(request)).map(_ => ())
  }

  def persistModifyRecipients(keepId: Id[Keep], eventData: ModifyRecipients, source: Option[KeepEventSource]): Future[Option[CommonAndBasicKeepEvent]] = {
    import PersistModifyRecipients._
    if (!eventData.isValid) Future.successful(None)
    else {
      val request = Request(keepId, eventData, source)
      call(Shoebox.internal.persistModifyRecipients(), body = Json.toJson(request)).map { _.json.as[Response].internalAndExternalEvent }
    }
  }
}

object ShoeboxServiceClient {
  object InternKeep {
    case class Request(creator: Id[User], users: Set[Id[User]], emails: Set[EmailAddress], uriId: Id[NormalizedURI], url: String, title: Option[String], note: Option[String], source: Option[KeepSource])
    implicit val requestFormat: Format[Request] = Json.format[Request]
  }
  object RegisterMessageOnKeep {
    case class Request(keepId: Id[Keep], msg: CrossServiceMessage)
    implicit val requestFormat: Format[Request] = Json.format[Request]
  }

  object GetSlackTeamInfo {
    case class Response(teamInfo: InternalSlackTeamInfo)
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }

  object GetPersonalKeepRecipientsOnUris {
    case class Request(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess])
    case class Response(keepRecipientsByUriId: Map[Id[NormalizedURI], Set[CrossServiceKeepRecipients]])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }

  object GetRecipientsOnKeep {
    case class Response(users: Map[Id[User], BasicUser], libraries: Map[Id[Library], BasicLibrary], emails: Set[EmailAddress])
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }

  object PersistModifyRecipients {
    case class Request(keepId: Id[Keep], eventData: ModifyRecipients, source: Option[KeepEventSource])
    case class Response(internalAndExternalEvent: Option[CommonAndBasicKeepEvent]) // None if event was not created
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
}
