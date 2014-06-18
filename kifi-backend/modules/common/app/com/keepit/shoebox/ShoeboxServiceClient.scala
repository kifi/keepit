package com.keepit.shoebox

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.db.{State, ExternalId, Id, SequenceNumber}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{EmailAddress, ElectronicMail}
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper._
import com.keepit.search.{ActiveExperimentsCache, ActiveExperimentsKey, SearchConfigExperiment}
import com.keepit.social._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.{ScrapeRequest, Signature, HttpRedirect}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.ImmediateMap
import play.api.libs.json.Json._
import org.joda.time.DateTime
import com.keepit.eliza.model.ThreadItem
import com.keepit.common.time.internalTime.DateTimeJsonLongFormat
import com.keepit.model._
import com.keepit.model.ChangedURI
import com.keepit.social.BasicUserUserIdKey
import play.api.libs.json._
import com.keepit.common.usersegment.UserSegmentKey
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.heimdal.SanitizedKifiHit
import com.keepit.model.serialize.{UriIdAndSeqBatch, UriIdAndSeq}
import org.msgpack.ScalaMessagePack


trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
  private val ? = null

  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUser(userId: Id[User]): Future[Option[User]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User],BasicUser]]
  def getBasicUsersNoCache(users: Seq[Id[User]]): Future[Map[Id[User],BasicUser]]
  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(url: String, scrapeWanted: Boolean = false): Future[NormalizedURI]
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
  def getScrapedUriIdAndSeq(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[UriIdAndSeq]]
  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]]
  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]]
  def getBookmarks(userId: Id[User]): Future[Seq[Keep]]
  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fertchSize: Int): Future[Seq[Keep]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Keep]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]]
  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[ExperimentType]]]
  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]]
  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]]
  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]]
  def getFriends(userId: Id[User]): Future[Set[Id[User]]]
  def logEvent(userId: Id[User], event: JsObject) : Unit
  def createDeepLink(initiator: Option[Id[User]], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit
  def getDeepUrl(locator: DeepLocator, recipient: Id[User]): Future[String]
  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]]
  def kifiHit(clicker: Id[User], hit:SanitizedKifiHit):Future[Unit]
  def getScrapeInfo(uri:NormalizedURI):Future[ScrapeInfo]
  def assignScrapeTasks(zkId:Long, max:Int):Future[Seq[ScrapeRequest]]
  def isUnscrapableP(url: String, destinationUrl: Option[String]):Future[Boolean]
  def isUnscrapable(url: String, destinationUrl: Option[String]):Future[Boolean]
  def getLatestKeep(url: String): Future[Option[Keep]]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]]
  def saveBookmark(bookmark:Keep): Future[Keep]
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo]
  def saveNormalizedURI(uri:NormalizedURI):Future[NormalizedURI]
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit]
  def savePageInfo(pageInfo:PageInfo):Future[PageInfo]
  def getImageInfo(id:Id[ImageInfo]):Future[ImageInfo]
  def saveImageInfo(imgInfo:ImageInfo):Future[ImageInfo]
  def updateNormalizedURI(uriId: => Id[NormalizedURI], createdAt: => DateTime = ?,updatedAt: => DateTime = ?,externalId: => ExternalId[NormalizedURI] = ?,title: => Option[String] = ?,url: => String = ?,urlHash: => UrlHash = UrlHash(?),state: => State[NormalizedURI] = ?,seq: => SequenceNumber[NormalizedURI] = SequenceNumber(-1),screenshotUpdatedAt: => Option[DateTime] = ?,restriction: => Option[Restriction] = ?,normalization: => Option[Normalization] = ?,redirect: => Option[Id[NormalizedURI]] = ?,redirectTime: => Option[DateTime] = ?): Future[Unit]
  def recordPermanentRedirect(uri:NormalizedURI, redirect:HttpRedirect):Future[NormalizedURI]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]
  def getProxy(url:String):Future[Option[HttpProxy]]
  def getProxyP(url:String):Future[Option[HttpProxy]]
  def getFriendRequestsBySender(senderId: Id[User]): Future[Seq[FriendRequest]]
  def getUserValue(userId: Id[User], key: String): Future[Option[String]]
  def setUserValue(userId: Id[User], key: String, value: String): Unit
  def getUserSegment(userId: Id[User]): Future[UserSegment]
  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String]
  def triggerRawKeepImport(): Unit
  def triggerSocialGraphFetch(id: Id[SocialUserInfo]): Future[Unit]
  def getUserConnectionsChanged(seqNum: SequenceNumber[UserConnection], fetchSize: Int): Future[Seq[UserConnection]]
  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]]
  def isSensitiveURI(uri: String): Future[Boolean]
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]): Future[Unit]
  def getVerifiedAddressOwners(emailAddresses: Seq[EmailAddress]): Future[Map[EmailAddress, Id[User]]]
  def sendUnreadMessages(threadItems: Seq[ThreadItem], otherParticipants: Set[Id[User]], user: Id[User], title: String, deepLocator: DeepLocator, notificationUpdatedAt: DateTime): Future[Unit]
  def getAllURLPatterns(): Future[Seq[UrlPatternRule]]
  def updateScreenshots(nUriId: Id[NormalizedURI]): Future[Unit]
  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]]
  def getUriSummary(request: URISummaryRequest): Future[URISummary]
  def getUserImageUrl(userId: Id[User], width: Int): Future[String]
  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String]
  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]]
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]]
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
    userSessionExternalIdCache: UserSessionExternalIdCache,
    userConnectionsCache: UserConnectionIdCache,
    searchFriendsCache: SearchFriendsCache,
    userValueCache: UserValueCache,
    userConnCountCache: UserConnectionCountCache,
    userBookmarkCountCache: KeepCountCache,
    userSegmentCache: UserSegmentCache,
    extensionVersionCache: ExtensionVersionInstallationIdCache,
    urlPatternRuleAllCache: UrlPatternRuleAllCache,
    userImageUrlCache: UserImageUrlCache
  )

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient with Logging{

  val MaxUrlLength = 3000
  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

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
      call(Shoebox.internal.getUserOpt(id)).map {r =>
        r.json match {
          case JsNull => None
          case js: JsValue => Some(Json.fromJson[User](js).get)
        }
      }
    }
  }

  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = {
    consolidateSocialInfoByNetworkAndSocialIdReq(SocialUserInfoNetworkKey(networkType, id)){ k =>
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
    call(Shoebox.internal.getBookmarks(userId)).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Keep](js).get)
    }
  }

  def getBookmarksChanged(seqNum: SequenceNumber[Keep], fetchSize: Int): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarksChanged(seqNum, fetchSize), callTimeouts = longTimeout).map{ r =>
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

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarksByUriWithoutTitle(uriId), callTimeouts = longTimeout).map { r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Keep](js).get)
    }
  }

  def getLatestKeep(url: String): Future[Option[Keep]] = {
    call(Shoebox.internal.getLatestKeep(), callTimeouts = longTimeout, body = JsString(url)).map { r =>
      Json.fromJson[Option[Keep]](r.json).get
    }
  }

  def saveBookmark(bookmark: Keep): Future[Keep] = {
    call(Shoebox.internal.saveBookmark(), Json.toJson(bookmark), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Keep](r.json).get
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

  def getUser(userId: Id[User]): Future[Option[User]] = consolidateGetUserReq(userId){ key =>
    val user = cacheProvider.userIdCache.get(UserIdKey(key))
    if (user.isDefined) {
      Promise.successful(user).future
    }
    else {
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

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User],BasicUser]] = {
    cacheProvider.basicUserCache.bulkGetOrElseFuture(userIds.map{ BasicUserUserIdKey(_) }.toSet){ keys =>
      redundantDBConnectionCheck(keys)
      val payload = JsArray(keys.toSeq.map(x => JsNumber(x.userId.id)))
      call(Shoebox.internal.getBasicUsers(), payload).map{ res =>
        res.json.as[Map[String, BasicUser]].map{ u =>
          val id = Id[User](u._1.toLong)
          (BasicUserUserIdKey(id), u._2)
        }
      }
    }.map{ m => m.map{ case (k, v) => (k.userId, v) } }
  }

  def getBasicUsersNoCache(userIds: Seq[Id[User]]): Future[Map[Id[User],BasicUser]] = {
    call(Shoebox.internal.getBasicUsersNoCache(), JsArray(userIds.map(x => JsNumber(x.id)))).map{ res =>
      res.json.as[Map[String, BasicUser]].map { u => Id[User](u._1.toLong) -> u._2}
    }
  }

  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[EmailAddress]]] = {
    redundantDBConnectionCheck(userIds)
    implicit val idFormat = Id.format[User]
    val payload = JsArray(userIds.map{ x => Json.toJson(x)})
    call(Shoebox.internal.getEmailAddressesForUsers(), payload).map{ res =>
      log.debug(s"[res.request.trackingId] getEmailAddressesForUsers for users $userIds returns json ${res.json}")
      res.json.as[Map[String, Seq[EmailAddress]]].map{ case (id, emails) => Id[User](id.toLong) -> emails }.toMap
    }
  }

  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateSearchFriendsReq(SearchFriendsKey(userId)){ key=>
    cacheProvider.searchFriendsCache.get(key) match {
      case Some(friends) => Promise.successful(friends.map(Id[User]).toSet).future
      case _ =>
        call(Shoebox.internal.getSearchFriends(userId)).map {r =>
          r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
        }
    }
  }

  def getFriends(userId: Id[User]): Future[Set[Id[User]]] = consolidateUserConnectionsReq(UserConnectionIdKey(userId)){ key=>
    cacheProvider.userConnectionsCache.get(key) match {
      case Some(friends) => Promise.successful(friends.map(Id[User]).toSet).future
      case _ =>
        call(Shoebox.internal.getConnectedUsers(userId)).map {r =>
          r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
        }
    }
  }

  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]] = {
   call(Shoebox.internal.getUnfriends(userId)).map{ r =>
     Json.fromJson[Set[Long]](r.json).get.map{Id[User](_)}
   }
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
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
      call(Shoebox.internal.getNormalizedURIByURL(), JsString(url.take(MaxUrlLength)), callTimeouts = CallTimeouts(maxWaitTime = Some(400))).map { r => r.json match {
        case JsNull => None
        case js: JsValue => Some(Json.fromJson[NormalizedURI](js).get)
        case null => None
      }}

  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]] =
    call(Shoebox.internal.getNormalizedUriByUrlOrPrenormalize(), JsString(url.take(MaxUrlLength))).map { r =>
      (r.json \ "normalizedURI").asOpt[NormalizedURI].map(Left(_)) getOrElse Right((r.json \ "url").as[String])
    }

  def internNormalizedURI(url: String, scrapeWanted: Boolean): Future[NormalizedURI] = {
    val payload = Json.obj("url" -> url, "scrapeWanted" -> scrapeWanted)
    call(Shoebox.internal.internNormalizedURI, payload).map(r => Json.fromJson[NormalizedURI](r.json).get)
  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(Shoebox.internal.persistServerSearchEvent, metaData)
  }

  def getPhrasesChanged(seqNum: SequenceNumber[Phrase], fetchSize: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesChanged(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[Phrase]](r.json).get
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber[Collection], fetchSize: Int): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsChanged(seqNum, fetchSize), callTimeouts = longTimeout) map { r =>
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
    call(Shoebox.internal.getExperiments).map{r =>
      Json.fromJson[Seq[SearchConfigExperiment]](r.json).get
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.getExperiment(id)).map{ r =>
      Json.fromJson[SearchConfigExperiment](r.json).get
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.saveExperiment, Json.toJson(experiment)).map{ r =>
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
    val payload = JsArray(userIds.map{ x => Json.toJson(x)})
    call(Shoebox.internal.getExperimentsByUserIds(), payload).map{ res =>
      res.json.as[Map[String, Set[ExperimentType]]]
      .map{ case (id, exps) => Id[User](id.toLong) -> exps }.toMap
    }
  }

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]] = {
    call(Shoebox.internal.getExperimentGenerators()).map{ res =>
      res.json.as[Seq[ProbabilisticExperimentGenerator]]
    }
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
    call(Shoebox.internal.getIndexable(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[NormalizedURI]](r.json).get
    }
  }

  def getIndexableUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getIndexableUris(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getScrapedUris(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getScrapedUris(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getScrapedUriIdAndSeq(seqNum: SequenceNumber[NormalizedURI], fetchSize: Int): Future[Seq[UriIdAndSeq]] = {
    call(Shoebox.internal.getScrapedUriIdAndSeq(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      ScalaMessagePack.read[UriIdAndSeqBatch](r.bytes).batch
    }
  }

  def getUserIndexable(seqNum: SequenceNumber[User], fetchSize: Int): Future[Seq[User]] = {
    call(Shoebox.internal.getUserIndexable(seqNum, fetchSize), callTimeouts = longTimeout).map{ r =>
      r.json.as[JsArray].value.map{ x => Json.fromJson[User](x).get }
    }
  }

  def getHighestUriSeq(): Future[SequenceNumber[NormalizedURI]] = {
    call(Shoebox.internal.getHighestUriSeq()).map{ r =>
      r.json.as(SequenceNumber.format[NormalizedURI])
    }
  }

  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]] = {
    cacheProvider.userSessionExternalIdCache.get(UserSessionExternalIdKey(sessionId)) match {
      case Some(session) => Promise.successful(Some(session)).future
      case None =>
        call(Shoebox.internal.getSessionByExternalId(sessionId)).map { r =>
          r.json match {
            case jso: JsObject => Json.fromJson[UserSession](jso).asOpt
            case _ => None
          }
        }
    }
  }

  def logEvent(userId: Id[User], event: JsObject) : Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "event" -> event)
    call(Shoebox.internal.logEvent, payload)
  }

  def createDeepLink(initiator: Option[Id[User]], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit = {
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
    call(Shoebox.internal.getDeepUrl, payload).map{ r =>
      r.json.as[String]
    }
  }

  def getNormalizedUriUpdates(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI]): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = {
    call(Shoebox.internal.getNormalizedUriUpdates(lowSeq, highSeq), callTimeouts = longTimeout).map{ r =>
      var m = Vector.empty[(Id[NormalizedURI], NormalizedURI)]
      r.json match {
        case jso: JsValue => {
          val rv = jso.as[JsArray].value.foreach{  js =>
            val id = Id[NormalizedURI]((js \ "id").as[Long])
            val uri = Json.fromJson[NormalizedURI](js \ "uri").get
            m = m :+ (id, uri)
          }
          m
        }
        case _ =>  m
      }
    }
  }

  def kifiHit(clickerId: Id[User], hit:SanitizedKifiHit): Future[Unit] = {
    implicit val userIdFormat = Id.format[User]
    val payload = Json.obj(
      "clickerId" -> clickerId,
      "kifiHit" -> hit
    )
    call(Shoebox.internal.kifiHit, payload) map { r => Unit }
  }

  def assignScrapeTasks(zkId:Long, max: Int): Future[Seq[ScrapeRequest]] = {
    call(Shoebox.internal.assignScrapeTasks(zkId, max), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[Seq[ScrapeRequest]]
    }
  }

  def getScrapeInfo(uri: NormalizedURI): Future[ScrapeInfo] = {
    call(Shoebox.internal.getScrapeInfo(), Json.toJson(uri)).map { r =>
      r.json.as[ScrapeInfo]
    }
  }

  def saveScrapeInfo(info: ScrapeInfo): Future[ScrapeInfo] = {
    call(Shoebox.internal.saveScrapeInfo(), Json.toJson(info), callTimeouts = longTimeout).map { r =>
      r.json.as[ScrapeInfo]
    }
  }

  def savePageInfo(pageInfo:PageInfo):Future[PageInfo] = {
    call(Shoebox.internal.savePageInfo(), Json.toJson(pageInfo), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[PageInfo]
    }
  }

  def getImageInfo(id: Id[ImageInfo]): Future[ImageInfo] = {
    call(Shoebox.internal.getImageInfo(id)).map { r =>
      r.json.as[ImageInfo]
    }
  }

  def saveImageInfo(imgInfo:ImageInfo):Future[ImageInfo] = {
    call(Shoebox.internal.saveImageInfo(), Json.toJson(imgInfo), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[ImageInfo]
    }
  }

  @deprecated("Dangerous call. Use updateNormalizedURI instead.","2014-01-30")
  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI] = {
    call(Shoebox.internal.saveNormalizedURI(), Json.toJson(uri), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = {
    val json = Json.obj("state" -> state)
    call(Shoebox.internal.updateNormalizedURI(uriId), json, callTimeouts = longTimeout, routingStrategy = leaderPriority).imap(_ => {})
  }

  def updateNormalizedURI(uriId: => Id[NormalizedURI],
                          createdAt: => DateTime,
                          updatedAt: => DateTime,
                          externalId: => ExternalId[NormalizedURI],
                          title: => Option[String],
                          url: => String,
                          urlHash: => UrlHash,
                          state: => State[NormalizedURI],
                          seq: => SequenceNumber[NormalizedURI],
                          screenshotUpdatedAt: => Option[DateTime],
                          restriction: => Option[Restriction],
                          normalization: => Option[Normalization],
                          redirect: => Option[Id[NormalizedURI]],
                          redirectTime: => Option[DateTime]): Future[Unit] = {
    import com.keepit.common.strings.OptionWrappedJsObject
    val safeUrlHash = Option(urlHash).map(p => Option(p.hash)).flatten
    val safeSeq = Option(seq).map(v => if (v.value == -1L) None else Some(v)).flatten

    val safeJsonParams: Seq[(String, JsValueWrapper)] = Seq(
      "createdAt" -> Option(createdAt),
      "updatedAt" -> Option(updatedAt),
      "externalId" -> Option(externalId),
      "title" -> Option(title),
      "url" -> Option(url),
      "urlHash" -> safeUrlHash,
      "state" -> Option(state),
      "seq" -> safeSeq,
      "screenshotUpdatedAt" -> Option(screenshotUpdatedAt),
      "restriction" -> Option(restriction),
      "normalization" -> Option(normalization),
      "redirect" -> Option(redirect),
      "redirectTime" -> Option(redirectTime)
    )
    val payload = Json.obj(safeJsonParams: _*)
    val stripped = payload.stripJsNulls()
    call(Shoebox.internal.updateNormalizedURI(uriId), stripped, callTimeouts = longTimeout, routingStrategy = leaderPriority).imap(_ => {})
  }

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = {
    call(Shoebox.internal.recordPermanentRedirect(), JsArray(Seq(Json.toJson[NormalizedURI](uri), Json.toJson[HttpRedirect](redirect))), callTimeouts = longTimeout).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    val payload = Json.obj(
      "id" -> uriId.id,
      "signature" -> uriSignature.toBase64(),
      "url" -> candidateUrl,
      "normalization" -> candidateNormalization,
      "alternateUrls" -> alternateUrls
    )
    call(Shoebox.internal.recordScrapedNormalization(), payload, callTimeouts = longTimeout).imap(_ => {})
  }

  def getProxy(url:String):Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxy(url)).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getProxyP(url:String):Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxyP, Json.toJson(url), callTimeouts = longTimeout).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean] = {
    call(Shoebox.internal.isUnscrapable(url.take(MaxUrlLength), destinationUrl.map(_.take(MaxUrlLength)))).map { r =>
      r.json.as[Boolean]
    }
  }

  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean] = {
    val destUrl = if (destinationUrl.isDefined && url == destinationUrl.get) {
      log.debug(s"[isUnscrapableP] url==destUrl ${url}; ignored") // todo: fix calling code
      None
    } else destinationUrl map { dUrl =>
      log.debug(s"[isUnscrapableP] url($url) != destUrl($dUrl)")
      dUrl
    }
    val payload = JsArray(destUrl match {
      case Some(dUrl) => Seq(Json.toJson(url.take(MaxUrlLength)), Json.toJson(dUrl.take(MaxUrlLength)))
      case None => Seq(Json.toJson(url.take(MaxUrlLength)))
    })
    call(Shoebox.internal.isUnscrapableP, payload, callTimeouts = longTimeout).map { r =>
      r.json.as[Boolean]
    }
  }

  def getFriendRequestsBySender(senderId: Id[User]): Future[Seq[FriendRequest]] = {
    call(Shoebox.internal.getFriendRequestBySender(senderId)).map{ r =>
      r.json.as[JsArray].value.map{ x => Json.fromJson[FriendRequest](x).get}
    }
  }

  def getUserValue(userId: Id[User], key: String): Future[Option[String]] = {
    cacheProvider.userValueCache.getOrElseFutureOpt(UserValueKey(userId, key)) {
      call(Shoebox.internal.getUserValue(userId, key)).map(_.json.asOpt[String])
    }
  }

  def setUserValue(userId: Id[User], key: String, value: String): Unit = { call(Shoebox.internal.setUserValue(userId, key), JsString(value)) }

  def getUserSegment(userId: Id[User]): Future[UserSegment] = {
    cacheProvider.userSegmentCache.getOrElseFuture(UserSegmentKey(userId)){
      val friendsCount = cacheProvider.userConnCountCache.get(UserConnectionCountKey(userId))
      val bmsCount = cacheProvider.userBookmarkCountCache.get(KeepCountKey(Some(userId)))

      (friendsCount, bmsCount) match {
        case (Some(f), Some(bm)) => {
          val segment =  UserSegmentFactory(bm, f)
          Future.successful(segment)
        }
        case _ => call(Shoebox.internal.getUserSegment(userId)).map { x => Json.fromJson[UserSegment](x.json).get }
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
    call(Shoebox.internal.getUserConnectionsChanged(seqNum, fetchSize), callTimeouts = longTimeout).map{ r =>
      Json.fromJson[Seq[UserConnection]](r.json).get
    }
  }

  def getSearchFriendsChanged(seqNum: SequenceNumber[SearchFriend], fetchSize: Int): Future[Seq[SearchFriend]] = {
    call(Shoebox.internal.getSearchFriendsChanged(seqNum, fetchSize), callTimeouts = longTimeout).map{ r =>
      Json.fromJson[Seq[SearchFriend]](r.json).get
    }
  }

  def isSensitiveURI(uri: String): Future[Boolean] = {
    val payload = Json.obj("uri" -> uri)
    call(Shoebox.internal.isSensitiveURI(), payload).map{ r =>
      Json.fromJson[Boolean](r.json).get
    }
  }

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction]) = {
    val payload = r match {
      case Some(res) => Json.obj("uriId" -> id, "restriction" -> res)
      case None => Json.obj("uriId" -> id, "restriction" -> JsNull)
    }
    call(Shoebox.internal.updateURIRestriction(), payload).map{ r => }
  }

  def getVerifiedAddressOwners(emailAddresses: Seq[EmailAddress]): Future[Map[EmailAddress, Id[User]]] = {
    val payload = Json.obj("addresses" -> emailAddresses)
    call(Shoebox.internal.getVerifiedAddressOwners(), payload).map { response =>
      response.json.as[JsObject].value.map { case (address, id) =>
        EmailAddress(address) -> id.as[Id[User]]
      }.toMap
    }
  }

  def sendUnreadMessages(threadItems: Seq[ThreadItem], otherParticipants: Set[Id[User]], userId: Id[User], title: String,
                         deepLocator: DeepLocator, notificationUpdatedAt: DateTime): Future[Unit] = {
    implicit val userIdFormat = Id.format[User]
    val payload = Json.obj(
      "threadItems" -> threadItems,
      "otherParticipants" -> otherParticipants.toSeq,
      "userId" -> userId,
      "title" -> title,
      "deepLocator" -> deepLocator.value,
      "notificationUpdatedAt" -> notificationUpdatedAt
    )
    call(Shoebox.internal.sendUnreadMessages(), payload).imap(_ => {})
  }

  def getAllURLPatterns(): Future[Seq[UrlPatternRule]] = {
    cacheProvider.urlPatternRuleAllCache.getOrElseFuture(UrlPatternRuleAllKey()){
      call(Shoebox.internal.allURLPatternRules()).map{ r =>
        Json.fromJson[Seq[UrlPatternRule]](r.json).get
      }
    }
  }

  def updateScreenshots(nUriId: Id[NormalizedURI]): Future[Unit] = {
    call(Shoebox.internal.updateScreenshots(nUriId)).map { r => assert(r.status == 202); () }
  }

  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]] = {
    call(Shoebox.internal.getUriImage(nUriId)).map { r =>
      Json.fromJson[Option[String]](r.json).get
    }
  }

  def getUriSummary(request: URISummaryRequest): Future[URISummary] = {
    call(Shoebox.internal.getUriSummary, Json.toJson(request), callTimeouts = longTimeout).map{ r =>
      r.json.as[URISummary]
    }
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = {
    cacheProvider.userImageUrlCache.getOrElseFuture(UserImageUrlCacheKey(userId, width)) {
      call(Shoebox.internal.getUserImageUrl(userId.id, width)).map { r =>
        r.json.as[String]
      }
    }
  }

  def getUnsubscribeUrlForEmail(email: EmailAddress): Future[String] = {
    call(Shoebox.internal.getUnsubscribeUrlForEmail(email)).map{ r =>
      r.body
    }
  }

  def getIndexableSocialConnections(seqNum: SequenceNumber[SocialConnection], fetchSize: Int): Future[Seq[IndexableSocialConnection]] = {
    call(Shoebox.internal.getIndexableSocialConnections(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      r.json.as[Seq[IndexableSocialConnection]]
    }
  }
  def getIndexableSocialUserInfos(seqNum: SequenceNumber[SocialUserInfo], fetchSize: Int): Future[Seq[SocialUserInfo]] = {
    call(Shoebox.internal.getIndexableSocialUserInfos(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      r.json.as[Seq[SocialUserInfo]]
    }
  }
}
