package com.keepit.shoebox

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.net.HttpClient
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.zookeeper._
import com.keepit.model._
import com.keepit.search.ActiveExperimentsCache
import com.keepit.search.ActiveExperimentsKey
import com.keepit.search.SearchConfigExperiment
import play.api.libs.json._
import com.keepit.social._
import com.keepit.model.UserExperimentUserIdKey
import com.keepit.model.ExperimentType
import play.api.libs.json.JsArray
import com.keepit.model.ExternalUserIdKey
import com.keepit.model.SocialUserInfoUserKey
import com.keepit.model.BookmarkUriUserKey
import com.keepit.social.BasicUserUserIdKey
import com.keepit.social.SocialId
import com.keepit.model.NormalizedURIKey
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.UserConnectionIdKey
import play.api.libs.json.JsObject
import com.keepit.model.SocialUserInfoNetworkKey
import com.keepit.model.UserSessionExternalIdKey
import com.keepit.model.UserExternalIdKey
import com.keepit.common.concurrent.ExecutionContext

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUser(userId: Id[User]): Future[Option[User]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User],BasicUser]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]]
  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]]
  def internNormalizedURI(urls: JsObject): Future[NormalizedURI]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def sendMailToUser(userId: Id[User], email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]]
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]]
  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]]
  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]]
  def getBookmarksChanged(seqNum: SequenceNumber, fertchSize: Int): Future[Seq[Bookmark]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]]
  def getCommentRecipientIds(commentId: Id[Comment]): Future[Seq[Id[User]]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def getUserExperiments(userId: Id[User]): Future[Seq[ExperimentType]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]]
  def userChannelFanout(userId: Id[User], msg: JsArray): Seq[Future[Int]]
  def userChannelBroadcastFanout(msg: JsArray): Seq[Future[Int]]
  def userChannelCountFanout(): Seq[Future[Int]]
  def uriChannelFanout(uri: String, msg: JsArray): Seq[Future[Int]]
  def uriChannelCountFanout(): Seq[Future[Int]]
  def suggestExperts(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Future[Seq[Id[User]]]
  def getSearchFriends(userId: Id[User]): Future[Set[Id[User]]]
  def getFriends(userId: Id[User]): Future[Set[Id[User]]]
  def logEvent(userId: Id[User], event: JsObject) : Unit
  def createDeepLink(initiator: Id[User], recipient: Id[User], uriId: Id[NormalizedURI], locator: DeepLocator) : Unit
  def getNormalizedUriUpdates(lowSeq: Long, highSeq: Long): Future[Seq[(Id[NormalizedURI], NormalizedURI)]]
}

case class ShoeboxCacheProvider @Inject() (
    userExternalIdCache: UserExternalIdCache,
    uriIdCache: NormalizedURICache,
    clickHistoryCache: ClickHistoryUserIdCache,
    browsingHistoryCache: BrowsingHistoryUserIdCache,
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
    uriByUrlhashCache: NormalizedURIUrlHashCache)

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val port: Int,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient with Logging{

  // ExecutionContext
  implicit private[this] val executionContext = ExecutionContext.immediate

  // request consolidation
  private[this] val consolidateGetUserReq = new RequestConsolidator[Id[User], Option[User]](ttl = 30 seconds)
  private[this] val consolidateSearchFriendsReq = new RequestConsolidator[SearchFriendsKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateUserConnectionsReq = new RequestConsolidator[UserConnectionIdKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateGetExperimentsReq = new RequestConsolidator[String, Seq[SearchConfigExperiment]](ttl = 30 seconds)

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
    cacheProvider.socialUserNetworkCache.get(SocialUserInfoNetworkKey(networkType, id)) match {
      case Some(sui) => Promise.successful(Some(sui)).future
      case None => call(Shoebox.internal.getSocialUserInfoByNetworkAndSocialId(id.id, networkType.name)) map { resp =>
        Json.fromJson[SocialUserInfo](resp.json).asOpt
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
    call(Shoebox.internal.getBookmarksChanged(seqNum.value, fetchSize)).map{ r =>
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

  def getCommentRecipientIds(commentId: Id[Comment]): Future[Seq[Id[User]]] = {
    call(Shoebox.internal.getCommentRecipientIds(commentId)).map{ r =>
      Json.fromJson[Seq[Long]](r.json).get.map(Id[User](_))
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
    val query = userIds.mkString(",")
    call(Shoebox.internal.getUsers(query)).map { r =>
      Json.fromJson[Seq[User]](r.json).get
    }
  }

  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val (cachedUsers, needToGetUsers) = userIds.map({ u =>
      u -> cacheProvider.externalUserIdCache.get(ExternalUserIdKey(u))
    }).foldRight((Seq[Id[User]](), Seq[ExternalId[User]]())) { (uOpt, res) =>
      uOpt._2 match {
        case Some(uid) => (res._1 :+ uid, res._2)
        case None => (res._1, res._2 :+ uOpt._1)
      }
    }
    needToGetUsers match {
      case Seq() => Promise.successful(cachedUsers).future
      case users => call(Shoebox.internal.getUserIdsByExternalIds(needToGetUsers.mkString(","))).map { r =>
        cachedUsers ++ r.json.as[Seq[Long]].map(Id[User](_))
      }
    }
  }

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User],BasicUser]] = {
    cacheProvider.basicUserCache.bulkGetOrElseFuture(userIds.map{ BasicUserUserIdKey(_) }.toSet){ keys =>
      val query = keys.map(_.userId.id).mkString(",")
      call(Shoebox.internal.getBasicUsers(query)).map{ res =>
        res.json.as[Map[String, BasicUser]].map{ u =>
          val id = Id[User](u._1.toLong)
          (BasicUserUserIdKey(id), u._2)
        }
      }
    }.map{ m => m.map{ case (k, v) => (k.userId, v) } }
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

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(Id[NormalizedURI](uriId.id))) {
      call(Shoebox.internal.getNormalizedURI(uriId.id)).map(r => Json.fromJson[NormalizedURI](r.json).get)
    }
  }

  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val query = uriIds.mkString(",")
    call(Shoebox.internal.getNormalizedURIs(query)).map { r =>
      Json.fromJson[Seq[NormalizedURI]](r.json).get
    }
  }

  def getNormalizedURIByURL(url: String): Future[Option[NormalizedURI]] =
      call(Shoebox.internal.getNormalizedURIByURL(), JsString(url)).map { r => r.json match {
        case JsNull => None
        case js: JsValue => Some(Json.fromJson[NormalizedURI](js).get)
        case null => None
      }}

  def getNormalizedUriByUrlOrPrenormalize(url: String): Future[Either[NormalizedURI, String]] =
    call(Shoebox.internal.getNormalizedUriByUrlOrPrenormalize(), JsString(url)).map { r =>
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

  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesByPage(page, size)).map { r =>
      Json.fromJson[Seq[Phrase]](r.json).get
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsChanged(seqNum.value, fetchSize)) map { r =>
      Json.fromJson[Seq[Collection]](r.json).get
    }
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    call(Shoebox.internal.getBookmarksInCollection(collectionId)) map { r =>
      Json.fromJson[Seq[Bookmark]](r.json).get
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = consolidateGetExperimentsReq("active") { t =>
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

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]] = {
    call(Shoebox.internal.getCollectionsByUser(userId)).map { r =>
      Json.fromJson[Seq[Collection]](r.json).get
    }
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = {
    call(Shoebox.internal.getCollectionIdsByExternalIds(collIds.mkString(","))).map { r =>
      r.json.as[Seq[Long]].map(Id[Collection](_))
    }
  }

  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]] = {
    call(Shoebox.internal.getIndexable(seqNum, fetchSize)).map { r =>
      Json.fromJson[Seq[NormalizedURI]](r.json).get
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


}
