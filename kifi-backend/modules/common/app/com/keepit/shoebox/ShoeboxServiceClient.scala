package com.keepit.shoebox

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.db.{State, ExternalId, Id, SequenceNumber}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper._
import com.keepit.model._
import com.keepit.search.ActiveExperimentsCache
import com.keepit.search.ActiveExperimentsKey
import com.keepit.search.SearchConfigExperiment
import com.keepit.social._
import com.keepit.model.ExperimentType
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.{ScrapeRequest, Signature, HttpRedirect}
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.ImmediateMap
import play.api.libs.json.Json._
import org.joda.time.DateTime
import play.api.libs.json.JsString
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import com.keepit.common.usersegment.UserSegmentKey
import play.api.libs.json.JsObject
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess


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
  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[String]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(urls: JsObject): Future[NormalizedURI]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getPhrasesChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Phrase]]
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getUriIdsInCollection(id: Id[Collection]): Future[Seq[BookmarkUriAndTime]]
  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]]
  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]]
  def getIndexableUris(seqNum: Long, fetchSize: Int): Future[Seq[IndexableUri]]
  def getScrapedUris(seqNum: Long, fetchSize: Int): Future[Seq[IndexableUri]]
  def getHighestUriSeq(): Future[Long]
  def getUserIndexable(seqNum: Long, fetchSize: Int): Future[Seq[User]]
  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]]
  def getBookmarksChanged(seqNum: SequenceNumber, fertchSize: Int): Future[Seq[Bookmark]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]]
  def getExperimentsByUserIds(userIds: Seq[Id[User]]): Future[Map[Id[User], Set[ExperimentType]]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]]
  def userChannelFanout(userId: Id[User], msg: JsArray): Seq[Future[Int]]
  def userChannelBroadcastFanout(msg: JsArray): Seq[Future[Int]]
  def userChannelCountFanout(): Seq[Future[Int]]
  def uriChannelFanout(uri: String, msg: JsArray): Seq[Future[Int]]
  def uriChannelCountFanout(): Seq[Future[Int]]
  def suggestExperts(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Future[Seq[Id[User]]]
  def getUnfriends(userId: Id[User]): Future[Set[Id[User]]]
  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]]
  def getFriends(userId: Id[User]): Future[Set[Id[User]]]
  def logEvent(userId: Id[User], event: JsObject) : Unit
  def createDeepLink(initiator: Id[User], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit
  def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long): Future[Seq[(Id[NormalizedURI], NormalizedURI)]]
  def clickAttribution(clicker: Id[User], uriId: Id[NormalizedURI], keepers: ExternalId[User]*): Unit
  def getScrapeInfo(uri:NormalizedURI):Future[ScrapeInfo]
  def assignScrapeTasks(zkId:Long, max:Int):Future[Seq[ScrapeRequest]]
  def isUnscrapableP(url: String, destinationUrl: Option[String]):Future[Boolean]
  def isUnscrapable(url: String, destinationUrl: Option[String]):Future[Boolean]
  def getLatestBookmark(uriId: Id[NormalizedURI])(implicit timeout:Int = 10000): Future[Option[Bookmark]]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit timeout:Int = 10000): Future[Seq[Bookmark]]
  def saveBookmark(bookmark:Bookmark)(implicit timeout:Int = 10000): Future[Bookmark]
  def saveScrapeInfo(info:ScrapeInfo)(implicit timeout:Int = 10000):Future[ScrapeInfo]
  def saveNormalizedURI(uri:NormalizedURI)(implicit timeout:Int = 10000):Future[NormalizedURI]
  def updateNormalizedURI(uriId: => Id[NormalizedURI], createdAt: => DateTime = ?,updatedAt: => DateTime = ?,externalId: => ExternalId[NormalizedURI] = ?,title: => Option[String] = ?,url: => String = ?,urlHash: => UrlHash = UrlHash(?),state: => State[NormalizedURI] = ?,seq: => SequenceNumber = SequenceNumber(-1),screenshotUpdatedAt: => Option[DateTime] = ?,restriction: => Option[Restriction] = ?,normalization: => Option[Normalization] = ?,redirect: => Option[Id[NormalizedURI]] = ?,redirectTime: => Option[DateTime] = ?)(implicit timeout:Int = 10000): Future[Boolean]
  def recordPermanentRedirect(uri:NormalizedURI, redirect:HttpRedirect)(implicit timeout:Int = 10000):Future[NormalizedURI]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization): Future[Unit]
  def getProxy(url:String):Future[Option[HttpProxy]]
  def getProxyP(url:String):Future[Option[HttpProxy]]
  def scraped(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  def scrapeFailed(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  def getFriendRequestsBySender(senderId: Id[User]): Future[Seq[FriendRequest]]
  def getUserValue(userId: Id[User], key: String): Future[Option[String]]
  def setUserValue(userId: Id[User], key: String, value: String): Unit
  def getUserSegment(userId: Id[User]): Future[UserSegment]
  def getExtensionVersion(installationId: ExternalId[KifiInstallation]): Future[String]
  def triggerRawKeepImport(): Unit
  def triggerSocialGraphFetch(id: Id[SocialUserInfo]): Future[Unit]
  def getUserConnectionsChanged(seq: Long, fetchSize: Int): Future[Seq[UserConnection]]
  def getSearchFriendsChanged(seq: Long, fetchSize: Int): Future[Seq[SearchFriend]]
}

case class ShoeboxCacheProvider @Inject() (
    userExternalIdCache: UserExternalIdCache,
    uriIdCache: NormalizedURICache,
    bookmarkUriUserCache: BookmarkUriUserCache,
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
    userBookmarkCountCache: BookmarkCountCache,
    userSegmentCache: UserSegmentCache,
    extensionVersionCache: ExtensionVersionInstallationIdCache)

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val port: Int,
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

  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    call(Shoebox.internal.getBookmarks(userId)).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Bookmark](js).get)
    }
  }

  def getBookmarksChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Bookmark]] = {
    call(Shoebox.internal.getBookmarksChanged(seqNum.value, fetchSize), callTimeouts = longTimeout).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Bookmark](js).get)
    }
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = {
    cacheProvider.bookmarkUriUserCache.getOrElseFutureOpt(BookmarkUriUserKey(uriId, userId)) {
      call(Shoebox.internal.getBookmarkByUriAndUser(uriId, userId)).map { r =>
          Json.fromJson[Option[Bookmark]](r.json).get
      }
    }
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI])(implicit timeout: Int): Future[Seq[Bookmark]] = {
    call(Shoebox.internal.getBookmarksByUriWithoutTitle(uriId), callTimeouts = CallTimeouts(responseTimeout = Some(timeout))).map { r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Bookmark](js).get)
    }
  }

  def getLatestBookmark(uriId: Id[NormalizedURI])(implicit timeout: Int): Future[Option[Bookmark]] = {
    call(Shoebox.internal.getLatestBookmark(uriId), callTimeouts = CallTimeouts(responseTimeout = Some(timeout))).map { r =>
      Json.fromJson[Option[Bookmark]](r.json).get
    }
  }

  def saveBookmark(bookmark: Bookmark)(implicit timeout: Int): Future[Bookmark] = {
    call(Shoebox.internal.saveBookmark(), Json.toJson(bookmark), callTimeouts = CallTimeouts(responseTimeout = Some(timeout))).map { r =>
      Json.fromJson[Bookmark](r.json).get
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

  def getEmailAddressesForUsers(userIds: Seq[Id[User]]): Future[Map[Id[User], Seq[String]]] = {
    redundantDBConnectionCheck(userIds)
    implicit val idFormat = Id.format[User]
    val payload = JsArray(userIds.map{ x => Json.toJson(x)})
    call(Shoebox.internal.getEmailAddressesForUsers(), payload).map{ res =>
      log.info(s"[res.request.trackingId] getEmailAddressesForUsers for users $userIds returns json ${res.json}")
      res.json.as[Map[String, Seq[String]]].map{ case (id, emails) => Id[User](id.toLong) -> emails }.toMap
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
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(Id[NormalizedURI](uriId.id))) {
      call(Shoebox.internal.getNormalizedURI(uriId.id)).map(r => Json.fromJson[NormalizedURI](r.json).get)
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
      (r.json \ "url").asOpt[String] match {
        case Some(url) => Right(url)
        case None => Left(Json.fromJson[NormalizedURI](r.json \ "normalizedURI").get)
      }
    }

  def internNormalizedURI(urls: JsObject): Future[NormalizedURI] = {
    call(Shoebox.internal.internNormalizedURI, urls).map(r => Json.fromJson[NormalizedURI](r.json).get)
  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(Shoebox.internal.persistServerSearchEvent, metaData)
  }

  def getPhrasesChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesChanged(seqNum.value, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[Phrase]](r.json).get
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsChanged(seqNum.value, fetchSize), callTimeouts = longTimeout) map { r =>
      Json.fromJson[Seq[Collection]](r.json).get
    }
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    call(Shoebox.internal.getBookmarksInCollection(collectionId), callTimeouts = longTimeout) map { r =>
      Json.fromJson[Seq[Bookmark]](r.json).get
    }
  }

  def getUriIdsInCollection(collectionId: Id[Collection]): Future[Seq[BookmarkUriAndTime]] = {
    call(Shoebox.internal.getUriIdsInCollection(collectionId), callTimeouts = longTimeout) map { r =>
      Json.fromJson[Seq[BookmarkUriAndTime]](r.json).get
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

  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]] = {
    call(Shoebox.internal.getIndexable(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[NormalizedURI]](r.json).get
    }
  }

  def getIndexableUris(seqNum: Long, fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getIndexableUris(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getScrapedUris(seqNum: Long, fetchSize: Int): Future[Seq[IndexableUri]] = {
    call(Shoebox.internal.getScrapedUris(seqNum, fetchSize), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[IndexableUri]](r.json).get
    }
  }

  def getUserIndexable(seqNum: Long, fetchSize: Int): Future[Seq[User]] = {
    call(Shoebox.internal.getUserIndexable(seqNum, fetchSize), callTimeouts = longTimeout).map{ r =>
      r.json.as[JsArray].value.map{ x => Json.fromJson[User](x).get }
    }
  }

  def getHighestUriSeq(): Future[Long] = {
    call(Shoebox.internal.getHighestUriSeq()).map{ r =>
      r.json.as[Long]
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

  def userChannelFanout(userId: Id[User], msg: JsArray): Seq[Future[Int]] = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "msg" -> msg)
    broadcast(Shoebox.internal.userChannelFanout(), payload).map { futResp =>
      futResp.map { r =>
        r.body.toInt
      }
    }
  }

  def userChannelBroadcastFanout(msg: JsArray): Seq[Future[Int]] = {
    broadcast(Shoebox.internal.userChannelBroadcastFanout(), msg).map { futResp =>
      futResp.map { r =>
        r.body.toInt
      }
    }
  }

  def userChannelCountFanout(): Seq[Future[Int]] = {
    broadcast(Shoebox.internal.userChannelCountFanout()).map { futResp =>
      futResp.map { r =>
        r.body.toInt
      }
    }
  }

  def uriChannelFanout(uri: String, msg: JsArray): Seq[Future[Int]] = {
    val payload = Json.obj("uri" -> uri, "msg" -> msg)
    broadcast(Shoebox.internal.uriChannelFanout(), payload).map { futResp =>
      futResp.map { r =>
        r.body.toInt
      }
    }
  }

  def uriChannelCountFanout(): Seq[Future[Int]] = {
    broadcast(Shoebox.internal.uriChannelCountFanout()).map { futResp =>
      futResp.map { r =>
        r.body.toInt
      }
    }
  }

  def suggestExperts(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Future[Seq[Id[User]]] = {
    val payload = JsArray(urisAndKeepers.map{ case (uri, users) =>
      Json.obj("uri" -> JsNumber(uri.id), "users" -> JsArray(users.map{_.id}.map{JsNumber(_)}) )
    })
    call(Shoebox.internal.suggestExperts(), payload).map{ r =>
      r.json match {
        case jso: JsValue => jso.as[JsArray].value.map{x => x.as[Long]}.map{Id[User](_)}
        case _ => List.empty[Id[User]]
      }
    }
  }

  def logEvent(userId: Id[User], event: JsObject) : Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "event" -> event)
    call(Shoebox.internal.logEvent, payload)
  }

  def createDeepLink(initiator: Id[User], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit = {
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

  def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long): Future[Seq[(Id[NormalizedURI], NormalizedURI)]] = {
    call(Shoebox.internal.getNormalizedUriUpdates(lowSeq, highSeq)).map{ r =>
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

  def clickAttribution(clicker: Id[User], uri: Id[NormalizedURI], keepers: ExternalId[User]*): Unit = {
    implicit val userFormatter = Id.format[User]
    implicit val uriFormatter = Id.format[NormalizedURI]
    val payload = Json.obj(
      "clicker" -> JsNumber(clicker.id),
      "uriId" -> JsNumber(uri.id),
      "keepers" -> JsArray(keepers.map(id => JsString(id.id)))
    )
    call(Shoebox.internal.clickAttribution, payload)
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

  def saveScrapeInfo(info: ScrapeInfo)(implicit timeout:Int): Future[ScrapeInfo] = {
    call(Shoebox.internal.saveScrapeInfo(), Json.toJson(info), callTimeouts = longTimeout).map { r =>
      r.json.as[ScrapeInfo]
    }
  }

  @deprecated("Dangerous call. Use updateNormalizedURI instead.","2014-01-30")
  def saveNormalizedURI(uri:NormalizedURI)(implicit timeout:Int): Future[NormalizedURI] = {
    call(Shoebox.internal.saveNormalizedURI(), Json.toJson(uri), callTimeouts = CallTimeouts(responseTimeout = Some(timeout))).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def updateNormalizedURI(uriId: => Id[NormalizedURI],
                          createdAt: => DateTime,
                          updatedAt: => DateTime,
                          externalId: => ExternalId[NormalizedURI],
                          title: => Option[String],
                          url: => String,
                          urlHash: => UrlHash,
                          state: => State[NormalizedURI],
                          seq: => SequenceNumber,
                          screenshotUpdatedAt: => Option[DateTime],
                          restriction: => Option[Restriction],
                          normalization: => Option[Normalization],
                          redirect: => Option[Id[NormalizedURI]],
                          redirectTime: => Option[DateTime])(implicit timeout:Int): Future[Boolean] = {
    import com.keepit.common.strings.OptionWrappedJsObject
    import NormalizedURI._
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
    call(Shoebox.internal.updateNormalizedURI(uriId), stripped).map { resp =>
      resp.json.asOpt[Boolean].getOrElse(false)
    }
  }

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect)(implicit timeout:Int): Future[NormalizedURI] = {
    call(Shoebox.internal.recordPermanentRedirect(), JsArray(Seq(Json.toJson[NormalizedURI](uri), Json.toJson[HttpRedirect](redirect))), callTimeouts = CallTimeouts(responseTimeout = Some(timeout))).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization): Future[Unit] = {
    val payload = Json.obj(
      "id" -> uriId.id,
      "signature" -> uriSignature.toBase64(),
      "url" -> candidateUrl,
      "normalization" -> candidateNormalization
    )
    call(Shoebox.internal.recordScrapedNormalization(), payload, callTimeouts = longTimeout).imap(_ => {})
  }

  def getProxy(url:String):Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxy(url)).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getProxyP(url:String):Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxyP, Json.toJson(url)).map { r =>
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
      log.info(s"[isUnscrapableP] url==destUrl ${url}; ignored") // todo: fix calling code
      None
    } else destinationUrl map { dUrl =>
      log.info(s"[isUnscrapableP] url($url) != destUrl($dUrl)")
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

  def scraped(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = {
    val payload = Json.obj(
      "uri" -> Json.toJson(uri),
      "info" -> Json.toJson(info)
    )
    call(Shoebox.internal.scraped, payload).map { r =>
      r.json.asOpt[NormalizedURI]
    }
  }

  def scrapeFailed(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = {
    val payload = Json.obj(
      "uri" -> Json.toJson(uri),
      "info" -> Json.toJson(info)
    )
    call(Shoebox.internal.scrapeFailed, payload).map { r =>
      r.json.asOpt[NormalizedURI]
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
      val bmsCount = cacheProvider.userBookmarkCountCache.get(BookmarkCountKey(Some(userId)))

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

  def getUserConnectionsChanged(seqNum: Long, fetchSize: Int): Future[Seq[UserConnection]] = {
    call(Shoebox.internal.getUserConnectionsChanged(seqNum, fetchSize)).map{ r =>
      Json.fromJson[Seq[UserConnection]](r.json).get
    }
  }

  def getSearchFriendsChanged(seq: Long, fetchSize: Int): Future[Seq[SearchFriend]] = {
    call(Shoebox.internal.getSearchFriendsChanged(seq, fetchSize)).map{ r =>
      Json.fromJson[Seq[SearchFriend]](r.json).get
    }
  }
}
