package com.keepit.shoebox

import com.keepit.classify.{ NormalizedHostname, DomainInfo, Domain }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.store.ImageSize
import com.keepit.model.cache.{ UserSessionViewExternalIdKey, UserSessionViewExternalIdCache }
import com.keepit.notify.info.NotificationInfo
import com.keepit.notify.model.{ NotificationId, notificationIdMapFormat }
import com.keepit.rover.model.BasicImages
import com.keepit.shoebox.model.{ KeepImagesKey, KeepImagesCache }
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.model.view.{ LibraryMembershipView, UserSessionView }

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, ElectronicMail }
import com.keepit.common.net.{ URI, CallTimeouts, HttpClient }
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper._
import com.keepit.search.{ ActiveExperimentsCache, ActiveExperimentsKey, SearchConfigExperiment }
import com.keepit.social._
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeNotifier }
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.common.concurrent.ExecutionContext
import play.api.libs.json.Json._
import org.joda.time.DateTime
import com.keepit.common.time.internalTime.DateTimeJsonLongFormat
import com.keepit.model._
import com.keepit.social.BasicUserUserIdKey
import play.api.libs.json._
import com.keepit.common.usersegment.UserSegmentKey
import com.keepit.common.json.TupleFormat
import com.keepit.common.core._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.shoebox.model.IngestableUserIpAddress
import com.keepit.common.json.EitherFormat

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUser(userId: Id[User]): Future[Option[User]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User], BasicUser]]
  def getEmailAddressesForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]]
  def getEmailAddressForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(url: String, contentWanted: Boolean = false): Future[NormalizedURI]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]]
  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[NormalizedURI]]
  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getIndexableUrisWithContent(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]]
  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]]
  def getBookmarks(userId: Id[User]): Future[Seq[Keep]]
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
  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]]
  def getUserImageUrl(userId: Id[User], width: Int): Future[String]
  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String]
  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]]
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]]
  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]]
  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[KeepAndTags]]
  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]): Future[Seq[DelightedUserRegistrationInfo]]
  def getAllFakeUsers(): Future[Set[Id[User]]]
  def getInvitations(senderId: Id[User]): Future[Seq[Invitation]]
  def getSocialConnections(userId: Id[User]): Future[Seq[SocialUserBasicInfo]]
  def addInteractions(userId: Id[User], actions: Seq[(Either[Id[User], EmailAddress], String)]): Unit
  def processAndSendMail(email: EmailToSend): Future[Boolean]
  def getLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryView]]
  def getDetailedLibrariesChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[DetailedLibraryView]]
  def getLibraryMembershipsChanged(seqNum: SequenceNumber[LibraryMembership], fetchSize: Int): Future[Seq[LibraryMembershipView]]
  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String]): Future[Boolean]
  def newKeepsInLibraryForEmail(userId: Id[User], max: Int): Future[Seq[Keep]]
  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[BasicKeep]]]
  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Future[Map[Id[Library], BasicLibraryDetails]]
  def getKeepCounts(userIds: Set[Id[User]]): Future[Map[Id[User], Int]]
  def getKeepImages(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], BasicImages]]
  def getLibrariesWithWriteAccess(userId: Id[User]): Future[Set[Id[Library]]]
  def getUserActivePersonas(userId: Id[User]): Future[UserActivePersonas]
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
  def getLibraries(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], Library]]
  def getUserImages(userIds: Seq[Id[User]]): Future[Map[Id[User], String]]
  def getKeeps(keepIds: Seq[Id[Keep]]): Future[Map[Id[Keep], Keep]]
  def getLibraryUrls(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], String]]
  def getLibraryInfos(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], LibraryNotificationInfo]]
  def getLibraryOwners(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], User]]
  def getOrganizations(orgIds: Seq[Id[Organization]]): Future[Map[Id[Organization], Organization]]
  def getOrganizationInfos(orgIds: Seq[Id[Organization]]): Future[Map[Id[Organization], OrganizationNotificationInfo]]
  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues]
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
  userActivePersonaCache: UserActivePersonasCache,
  keepImagesCache: KeepImagesCache,
  primaryOrgForUserCache: PrimaryOrgForUserCache)

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
  private[this] val consolidateSocialInfoByNetworkAndSocialIdReq = new RequestConsolidator[SocialUserInfoNetworkKey, Option[SocialUserInfo]](ttl = 30 seconds)
  private[this] val consolidateSearchFriendsReq = new RequestConsolidator[SearchFriendsKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateUserConnectionsReq = new RequestConsolidator[UserConnectionIdKey, Set[Id[User]]](ttl = 3 seconds)

  private def redundantDBConnectionCheck(request: Iterable[_]) {
    if (request.isEmpty) {
      airbrakeNotifier.notify("ShoeboxServiceClient: trying to call DB with empty list.")
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

  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = {
    consolidateSocialInfoByNetworkAndSocialIdReq(SocialUserInfoNetworkKey(networkType, id)) { k =>
      cacheProvider.socialUserNetworkCache.get(k) match {
        case Some(sui) => Future.successful(Some(sui))
        case None => call(Shoebox.internal.getSocialUserInfoByNetworkAndSocialId(id.id, networkType.name)) map { resp =>
          resp.json.asOpt[SocialUserInfo]
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

  def getBookmarks(userId: Id[User]): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarks(userId)).map { r =>
      r.json.as[Seq[Keep]]
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

  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean] = {
    val payload = Json.obj(
      "user" -> userId.id,
      "email" -> Json.toJson(email)
    )
    call(Shoebox.internal.sendMailToUser(), payload).map(r => r.body.toBoolean)
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

  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val (cachedUsers, needToGetUsers) = userIds.map({ u =>
      u -> cacheProvider.externalUserIdCache.get(ExternalUserIdKey(u))
    }).foldRight((Map[ExternalId[User], Id[User]](), Seq[ExternalId[User]]())) { (uOpt, res) =>
      uOpt._2 match {
        case Some(uid) => (res._1 + (uOpt._1 -> uid), res._2)
        case None => (res._1, res._2 :+ uOpt._1)
      }
    }
    (needToGetUsers match {
      case Seq() => Future.successful(cachedUsers)
      case users => call(Shoebox.internal.getUserIdsByExternalIds(needToGetUsers.mkString(","))).map { r =>
        cachedUsers ++ users.zip(r.json.as[Seq[Id[User]]])
      }
    }) map { extId2Id =>
      userIds.map(extId2Id(_))
    }

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

  def getEmailAddressesForUsers(userIds: Set[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getEmailAddressesForUsers(), payload, callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { res =>
      log.debug(s"[res.request.trackingId] getEmailAddressesForUsers for users $userIds returns json ${res.json}")
      res.json.as[Map[String, Seq[EmailAddress]]].map { case (id, emails) => Id[User](id.toLong) -> emails }.toMap
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

  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    redundantDBConnectionCheck(uriIds)
    val query = uriIds.mkString(",")
    call(Shoebox.internal.getNormalizedURIs(query)).map { r =>
      r.json.as[Seq[NormalizedURI]]
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
    call(Shoebox.internal.internNormalizedURI, payload).map(r => r.json.as[NormalizedURI])
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

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[NormalizedURI]] = {
    call(Shoebox.internal.getIndexable(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[NormalizedURI]]
    }
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
    call(Shoebox.internal.triggerRawKeepImport(), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority)
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]): Future[Unit] = {
    call(call = Shoebox.internal.triggerSocialGraphFetch(socialUserInfoId), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map(_ => ())(ExecutionContext.immediate)
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

  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    call(Shoebox.internal.getCandidateURIs(), body = Json.toJson(uris), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[Boolean]]
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

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]): Future[Seq[DelightedUserRegistrationInfo]] = {
    call(Shoebox.internal.getLapsedUsersForDelighted(maxCount, skipCount, after, before), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[DelightedUserRegistrationInfo]]
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

  def newKeepsInLibraryForEmail(userId: Id[User], max: Int): Future[Seq[Keep]] = {
    call(Shoebox.internal.newKeepsInLibraryForEmail(userId, max)).map(_.json.as[Seq[Keep]])
  }

  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[BasicKeep]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty[Id[NormalizedURI], Set[BasicKeep]]) else {
      call(Shoebox.internal.getBasicKeeps(userId), Json.toJson(uriIds), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[BasicKeep]]
        r.json.as[Seq[(Id[NormalizedURI], Set[BasicKeep])]].toMap
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

  def getUserActivePersonas(userId: Id[User]): Future[UserActivePersonas] = {
    cacheProvider.userActivePersonaCache.getOrElseFuture(UserActivePersonasKey(userId)) {
      call(Shoebox.internal.getUserActivePersonas(userId), callTimeouts = longTimeout).map { _.json.as[UserActivePersonas] }
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
    val payload = Json.obj("domainNames" -> domainNames)
    call(Shoebox.internal.internDomainsByDomainNames(), payload, routingStrategy = offlinePriority).map { _.json.as[Map[String, DomainInfo]].map { case (hostname: String, domainInfo: DomainInfo) => NormalizedHostname(hostname) -> domainInfo } }
  }

  def getOrganizationMembers(orgId: Id[Organization]): Future[Set[Id[User]]] = {
    call(Shoebox.internal.getOrganizationMembers(orgId)).map(_.json.as[Set[Id[User]]])
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

  def getLibraries(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], Library]] = {
    val payload = Json.toJson(libraryIds)
    call(Shoebox.internal.getLibraries(), payload).map { _.json.as[Map[Id[Library], Library]] }
  }

  def getUserImages(userIds: Seq[Id[User]]): Future[Map[Id[User], String]] = {
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getUserImages(), payload).map { _.json.as[Map[Id[User], String]] }
  }

  def getKeeps(keepIds: Seq[Id[Keep]]): Future[Map[Id[Keep], Keep]] = {
    val payload = Json.toJson(keepIds)
    call(Shoebox.internal.getKeeps(), payload).map { _.json.as[Map[Id[Keep], Keep]] }
  }

  def getLibraryUrls(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], String]] = {
    val payload = Json.toJson(libraryIds)
    call(Shoebox.internal.getLibraryUrls(), payload).map { _.json.as[Map[Id[Library], String]] }
  }

  def getLibraryInfos(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], LibraryNotificationInfo]] = {
    val payload = Json.toJson(libraryIds)
    call(Shoebox.internal.getLibraryInfos(), payload).map { _.json.as[Map[Id[Library], LibraryNotificationInfo]] }
  }

  def getLibraryOwners(libraryIds: Seq[Id[Library]]): Future[Map[Id[Library], User]] = {
    val payload = Json.toJson(libraryIds)
    call(Shoebox.internal.getLibraryOwners(), payload).map { _.json.as[Map[Id[Library], User]] }
  }

  def getOrganizations(orgIds: Seq[Id[Organization]]): Future[Map[Id[Organization], Organization]] = {
    val payload = Json.toJson(orgIds)
    call(Shoebox.internal.getOrganizations(), payload).map { _.json.as[Map[Id[Organization], Organization]] }
  }

  def getOrganizationInfos(orgIds: Seq[Id[Organization]]): Future[Map[Id[Organization], OrganizationNotificationInfo]] = {
    val payload = Json.toJson(orgIds)
    call(Shoebox.internal.getOrganizationInfos(), payload).map { _.json.as[Map[Id[Organization], OrganizationNotificationInfo]] }
  }

  def getOrgTrackingValues(orgId: Id[Organization]): Future[OrgTrackingValues] = {
    call(Shoebox.internal.getOrgTrackingValues(orgId)).map { _.json.as[OrgTrackingValues] }
  }
}
