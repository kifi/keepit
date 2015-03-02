package com.keepit.shoebox

import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.store.ImageSize
import com.keepit.model.cache.{ UserSessionViewExternalIdKey, UserSessionViewExternalIdCache }
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.model.view.{ LibraryMembershipView, UserSessionView }

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future, Promise }
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
import com.keepit.common.core._
import play.api.libs.json.Json._
import org.joda.time.DateTime
import com.keepit.eliza.model.ThreadItem
import com.keepit.common.time.internalTime.DateTimeJsonLongFormat
import com.keepit.model._
import com.keepit.social.BasicUserUserIdKey
import play.api.libs.json._
import com.keepit.common.usersegment.UserSegmentKey
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.json.TupleFormat

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUser(userId: Id[User]): Future[Option[User]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User], BasicUser]]
  def getBasicUsersNoCache(users: Seq[Id[User]]): Future[Map[Id[User], BasicUser]]
  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]]
  def getPrimaryEmailAddressForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(url: URI, scrapeWanted: Boolean = false): Future[NormalizedURI]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]]
  def getUriIdsInCollection(id: Id[Collection]): Future[Seq[KeepUriAndTime]]
  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int): Future[Seq[Collection]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]]
  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[NormalizedURI]]
  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]]
  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]]
  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]]
  def getBookmarks(userId: Id[User]): Future[Seq[Keep]]
  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]]
  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[ExperimentType]]]
  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]]
  def getUsersByExperiment(experimentType: ExperimentType): Future[Set[User]]
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
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]): Future[Unit]
  def getUriSummary(request: URISummaryRequest): Future[URISummary]
  def getUriSummaries(uriIds: Seq[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], URISummary]]
  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]]
  def getUserImageUrl(userId: Id[User], width: Int): Future[String]
  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String]
  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]]
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]]
  def getEmailAccountUpdates(seqNum: SequenceNumber[EmailAccountUpdate], fetchSize: Int): Future[Seq[EmailAccountUpdate]]
  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryAndMemberships]]
  def getLibrariesAndMembershipIdsChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryAndMembershipsIds]]
  def getLibraryMembership(id: Id[LibraryMembership]): Future[LibraryMembership]
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
  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String], hashedPassPhrase: Option[HashedPassPhrase]): Future[Boolean]
  def newKeepsInLibraryForEmail(userId: Id[User], max: Int): Future[Seq[Keep]]
  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[BasicKeep]]]
  def getBasicLibraryStatistics(libraryIds: Set[Id[Library]]): Future[Map[Id[Library], BasicLibraryStatistics]]
  def getKeepCounts(userIds: Set[Id[User]]): Future[Map[Id[User], Int]]
  def getLibraryImageUrls(libraryIds: Set[Id[Library]], idealImageSize: ImageSize): Future[Map[Id[Library], String]]
  def getLibrariesWithWriteAccess(userId: Id[User]): Future[Set[Id[Library]]]
  def getUserActivePersonas(userId: Id[User]): Future[UserActivePersonas]
}

case class ShoeboxCacheProvider @Inject() (
  userExternalIdCache: UserExternalIdCache,
  uriIdCache: NormalizedURICache,
  uriSummaryCache: URISummaryCache,
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
  userActivePersonaCache: UserActivePersonasCache)

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  cacheProvider: ShoeboxCacheProvider,
  libraryMembershipCache: LibraryMembershipIdCache,
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
          case js: JsValue => Some(Json.fromJson[User](js).get)
        }
      }
    }
  }

  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = {
    consolidateSocialInfoByNetworkAndSocialIdReq(SocialUserInfoNetworkKey(networkType, id)) { k =>
      cacheProvider.socialUserNetworkCache.get(k) match {
        case Some(sui) => Promise.successful(Some(sui)).future
        case None => call(Shoebox.internal.getSocialUserInfoByNetworkAndSocialId(id.id, networkType.name)) map { resp =>
          Json.fromJson[SocialUserInfo](resp.json).asOpt
        }
      }
    }
  }

  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]] = {
    cacheProvider.socialUserCache.get(SocialUserInfoUserKey(userId)) match {
      case Some(sui) => Promise.successful(sui).future
      case None => call(Shoebox.internal.getSocialUserInfosByUserId(userId)) map { resp =>
        Json.fromJson[Seq[SocialUserInfo]](resp.json).get
      }
    }
  }

  def getBookmarks(userId: Id[User]): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarks(userId)).map { r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Keep](js).get)
    }
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarksChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Keep](js).get)
    }
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]] = {
    cacheProvider.bookmarkUriUserCache.getOrElseFutureOpt(KeepUriUserKey(uriId, userId)) {
      call(Shoebox.internal.getBookmarkByUriAndUser(uriId, userId)).map { r =>
        Json.fromJson[Option[Keep]](r.json).get
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
      Promise.successful(user).future
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
      case Seq() => Promise.successful(cachedUsers).future
      case users => call(Shoebox.internal.getUserIdsByExternalIds(needToGetUsers.mkString(","))).map { r =>
        cachedUsers ++ users.zip(r.json.as[Seq[Long]].map(Id[User](_)))
      }
    }) map { extId2Id =>
      userIds.map(extId2Id(_))
    }

  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    cacheProvider.basicUserCache.bulkGetOrElseFuture(userIds.map { BasicUserUserIdKey(_) }.toSet) { keys =>
      redundantDBConnectionCheck(keys)
      val payload = JsArray(keys.toSeq.map(x => JsNumber(x.userId.id)))
      call(Shoebox.internal.getBasicUsers(), payload).map { res =>
        res.json.as[Map[String, BasicUser]].map { u =>
          val id = Id[User](u._1.toLong)
          (BasicUserUserIdKey(id), u._2)
        }
      }
    }.map { m => m.map { case (k, v) => (k.userId, v) } }
  }

  def getBasicUsersNoCache(userIds: Seq[Id[User]]): Future[Map[Id[User], BasicUser]] = {
    call(Shoebox.internal.getBasicUsersNoCache(), JsArray(userIds.map(x => JsNumber(x.id)))).map { res =>
      res.json.as[Map[String, BasicUser]].map { u => Id[User](u._1.toLong) -> u._2 }
    }
  }

  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    implicit val idFormat = Id.format[User]
    val payload = JsArray(userIds.map { x => Json.toJson(x) })
    call(Shoebox.internal.getEmailAddressesForUsers(), payload).map { res =>
      log.debug(s"[res.request.trackingId] getEmailAddressesForUsers for users $userIds returns json ${res.json}")
      res.json.as[Map[String, Seq[EmailAddress]]].map { case (id, emails) => Id[User](id.toLong) -> emails }.toMap
    }
  }

  def getPrimaryEmailAddressForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Option[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    val payload = Json.toJson(userIds)
    call(Shoebox.internal.getPrimaryEmailAddressForUsers(), payload) map { _.json.as[Map[Id[User], Option[EmailAddress]]] }
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateSearchFriendsReq(SearchFriendsKey(userId)) { key =>
    cacheProvider.searchFriendsCache.get(key) match {
      case Some(friends) => Promise.successful(friends.map(Id[User]).toSet).future
      case _ =>
        call(Shoebox.internal.getSearchFriends(userId)).map { r =>
          r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
        }
    }
  }

  def getFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateUserConnectionsReq(UserConnectionIdKey(userId)) { key =>
    cacheProvider.userConnectionsCache.get(key) match {
      case Some(friends) => Promise.successful(friends.map(Id[User]).toSet).future
      case _ =>
        call(Shoebox.internal.getConnectedUsers(userId)).map { r =>
          r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
        }
    }
  }

  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]] = {
    call(Shoebox.internal.getUnfriends(userId)).map { r =>
      Json.fromJson[Set[Long]](r.json).get.map { Id[User](_) }
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
      Json.fromJson[Seq[NormalizedURI]](r.json).get
    }
  }

  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]] =
    call(Shoebox.internal.getNormalizedURIByURL(), JsString(url.take(MaxUrlLength)), callTimeouts = CallTimeouts(maxWaitTime = Some(400))).map { r =>
      r.json match {
        case JsNull => None
        case js: JsValue => Some(Json.fromJson[NormalizedURI](js).get)
        case null => None
      }
    }

  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]] =
    call(Shoebox.internal.getNormalizedUriByUrlOrPrenormalize(), JsString(url.take(MaxUrlLength))).map { r =>
      (r.json \ "normalizedURI").asOpt[NormalizedURI].map(Left(_)) getOrElse Right((r.json \ "url").as[String])
    }

  def internNormalizedURI(url: URI, scrapeWanted: Boolean): Future[NormalizedURI] = {
    val payload = Json.obj("url" -> url.toString(), "scrapeWanted" -> scrapeWanted)
    call(Shoebox.internal.internNormalizedURI, payload).map(r => Json.fromJson[NormalizedURI](r.json).get)
  }

  def persistServerSearchEvent(metaData: JsObject): Unit = {
    call(Shoebox.internal.persistServerSearchEvent, metaData)
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[Phrase]](r.json).get
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority) map { r =>
      Json.fromJson[Seq[Collection]](r.json).get
    }
  }

  def getUriIdsInCollection(collectionId: Id[Collection]): Future[Seq[KeepUriAndTime]] = {
    call(Shoebox.internal.getUriIdsInCollection(collectionId), callTimeouts = longTimeout) map { r =>
      Json.fromJson[Seq[KeepUriAndTime]](r.json).get
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    cacheProvider.activeSearchConfigExperimentsCache.getOrElseFuture(ActiveExperimentsKey) {
      call(Shoebox.internal.getActiveExperiments).map { r =>
        Json.fromJson[Seq[SearchConfigExperiment]](r.json).get
      }
    }
  }
  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    call(Shoebox.internal.getExperiments).map { r =>
      Json.fromJson[Seq[SearchConfigExperiment]](r.json).get
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.getExperiment(id)).map { r =>
      Json.fromJson[SearchConfigExperiment](r.json).get
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.saveExperiment, Json.toJson(experiment)).map { r =>
      Json.fromJson[SearchConfigExperiment](r.json).get
    }
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
      case Some(states) => Promise.successful(states).future
      case None => call(Shoebox.internal.getUserExperiments(userId)).map { r =>
        r.json.as[Set[String]].map(ExperimentType(_)).toSeq
      }
    }
  }

  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[ExperimentType]]] = {
    redundantDBConnectionCheck(userIds)
    implicit val idFormat = Id.format[User]
    val payload = JsArray(userIds.map { x => Json.toJson(x) })
    call(Shoebox.internal.getExperimentsByUserIds(), payload).map { res =>
      res.json.as[Map[String, Set[ExperimentType]]]
        .map { case (id, exps) => Id[User](id.toLong) -> exps }.toMap
    }
  }

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = {
    call(Shoebox.internal.getExperimentGenerators()).map { res =>
      res.json.as[Seq[ProbabilisticExperimentGenerator]]
    }
  }

  def getUsersByExperiment(experimentType: ExperimentType): Future[Set[User]] = {
    call(Shoebox.internal.getUsersByExperiment(experimentType)).map(_.json.as[Set[User]])
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsByUser(userId)).map { r =>
      Json.fromJson[Seq[Collection]](r.json).get
    }
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = {
    redundantDBConnectionCheck(collIds)
    call(Shoebox.internal.getCollectionIdsByExternalIds(collIds.mkString(","))).map { r =>
      r.json.as[Seq[Long]].map(Id[Collection](_))
    }
  }

  def getIndexable(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[NormalizedURI]] = {
    call(Shoebox.internal.getIndexable(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[NormalizedURI]](r.json).get
    }
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getIndexableUris(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getScrapedUris(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]] = {
    call(Shoebox.internal.getUserIndexable(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[JsArray].value.map { x => Json.fromJson[User](x).get }
    }
  }

  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]] = {
    call(Shoebox.internal.getHighestUriSeq(), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as(SequenceNumber.format[NormalizedURI])
    }
  }

  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]] = {
    cacheProvider.userSessionExternalIdCache.get(UserSessionViewExternalIdKey(sessionId)) match {
      case Some(session) => Promise.successful(Some(session)).future
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
    callLeader(Shoebox.internal.triggerRawKeepImport())
  }

  def triggerSocialGraphFetch(socialUserInfoId: Id[SocialUserInfo]): Future[Unit] = {
    callLeader(call = Shoebox.internal.triggerSocialGraphFetch(socialUserInfoId), callTimeouts = CallTimeouts(responseTimeout = Some(300000))).map(_ => ())(ExecutionContext.immediate)
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

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]) = {
    val payload = r match {
      case Some(res) => Json.obj("uriId" -> id, "restriction" -> res)
      case None => Json.obj("uriId" -> id, "restriction" -> JsNull)
    }
    call(Shoebox.internal.updateURIRestriction(), payload).map { r => }
  }

  def getUriSummary(request: URISummaryRequest): Future[URISummary] = {
    val tracer = new StackTrace()
    val timeout = if (request.waiting) superExtraLongTimeoutJustForEmbedly else longTimeout
    val res = call(Shoebox.internal.getUriSummary, Json.toJson(request), callTimeouts = timeout).map { r =>
      r.json.as[URISummary]
    }
    res.onFailure {
      case t: Throwable => airbrakeNotifier.notify(s"call to getUriSummary failed on request $request", tracer.withCause(t))
    }
    res
  }

  def getUriSummaries(uriIds: Seq[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], URISummary]] = {
    redundantDBConnectionCheck(uriIds)
    val keys = uriIds.map(URISummaryKey)
    cacheProvider.uriSummaryCache.bulkGetOrElseFuture(keys.toSet) { missing =>
      val missingKeysSeq = missing.toSeq
      val request = Json.obj("uriIds" -> missingKeysSeq.map(_.id))
      call(Shoebox.internal.getUriSummaries, request, callTimeouts = superExtraLongTimeoutJustForEmbedly).map { r =>
        Json.fromJson[Seq[URISummary]](r.json).get
      } map { uriSummaries =>
        (missingKeysSeq zip uriSummaries) toMap
      }
    }.map(_ map { case (k, v) => (k.id, v) })
  }

  def getCandidateURIs(uris: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    call(Shoebox.internal.getCandidateURIs(), body = Json.toJson(uris), callTimeouts = longTimeout).map { r =>
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

  def getLibrariesAndMembershipsChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryAndMemberships]] = {
    call(Shoebox.internal.getLibrariesAndMembershipsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[LibraryAndMemberships]]
    }
  }

  def getLibrariesAndMembershipIdsChanged(seqNum: SequenceNumber[Library], fetchSize: Int): Future[Seq[LibraryAndMembershipsIds]] = {
    call(Shoebox.internal.getLibrariesAndMembershipIdsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[LibraryAndMembershipsIds]]
    }
  }

  def getLibraryMembership(id: Id[LibraryMembership]): Future[LibraryMembership] = libraryMembershipCache.getOrElseFuture(LibraryMembershipIdKey(id)) {
    call(Shoebox.internal.getLibraryMembership(id), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[LibraryMembership]
    }
  }

  def getKeepsAndTagsChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[KeepAndTags]] = {
    call(Shoebox.internal.getKeepsAndTagsChanged(seqNum, fetchSize), callTimeouts = extraLongTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[KeepAndTags]]
    }
  }

  def getLapsedUsersForDelighted(maxCount: Int, skipCount: Int, after: DateTime, before: Option[DateTime]): Future[Seq[DelightedUserRegistrationInfo]] = {
    call(Shoebox.internal.getLapsedUsersForDelighted(maxCount, skipCount, after, before), callTimeouts = longTimeout).map { r =>
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

  def canViewLibrary(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String], hashedPassPhrase: Option[HashedPassPhrase]): Future[Boolean] = {
    val body = Json.obj(
      "libraryId" -> libraryId,
      "userId" -> userId,
      "authToken" -> authToken,
      "passPhrase" -> Json.toJson(hashedPassPhrase))
    call(Shoebox.internal.canViewLibrary, body = body).map(_.json.as[Boolean])
  }

  def newKeepsInLibraryForEmail(userId: Id[User], max: Int): Future[Seq[Keep]] = {
    call(Shoebox.internal.newKeepsInLibraryForEmail(userId, max)).map(_.json.as[Seq[Keep]])
  }

  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[BasicKeep]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty[Id[NormalizedURI], Set[BasicKeep]]) else {
      call(Shoebox.internal.getBasicKeeps(userId), Json.toJson(uriIds)).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[BasicKeep]]
        r.json.as[Seq[(Id[NormalizedURI], Set[BasicKeep])]].toMap
      }
    }
  }

  def getBasicLibraryStatistics(libraryIds: Set[Id[Library]]): Future[Map[Id[Library], BasicLibraryStatistics]] = {
    if (libraryIds.isEmpty) Future.successful(Map.empty[Id[Library], BasicLibraryStatistics]) else {
      call(Shoebox.internal.getBasicLibraryStatistics, Json.toJson(libraryIds)).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[Library], BasicLibraryStatistics]
        r.json.as[Seq[(Id[Library], BasicLibraryStatistics)]].toMap
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

  def getLibraryImageUrls(libraryIds: Set[Id[Library]], idealImageSize: ImageSize): Future[Map[Id[Library], String]] = {
    if (libraryIds.isEmpty) Future.successful(Map.empty[Id[Library], String]) else {
      val payload = Json.obj(
        "libraryIds" -> libraryIds,
        "idealImageSize" -> idealImageSize
      )
      call(Shoebox.internal.getLibraryImageUrls, payload).map { r =>
        implicit val readsFormat = TupleFormat.tuple2Reads[Id[Library], String]
        r.json.as[Seq[(Id[Library], String)]].toMap
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
}
