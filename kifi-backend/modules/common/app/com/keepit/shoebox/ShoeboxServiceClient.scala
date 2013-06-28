package com.keepit.shoebox

import com.keepit.common.zookeeper.ServiceCluster
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import scala.concurrent.duration._
import com.google.inject.{Singleton, Provides, Inject}
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.ElectronicMail
import com.keepit.search.SearchConfigExperiment
import com.keepit.common.db.State
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.serializer._
import play.api.libs.json._
import com.keepit.search.ArticleSearchResult
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserUserIdCache
import com.keepit.common.social.BasicUserUserIdKey
import com.keepit.search.ActiveExperimentsCache
import com.keepit.search.ActiveExperimentsKey
import com.keepit.common.db.ExternalId
import com.keepit.search.ArticleSearchResultFactory
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.social.SocialNetworkType
import com.keepit.common.social.SocialId
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.ArticleHit
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Shoebox
import com.keepit.serializer.CollectionTupleSerializer.collectionTupleFormat
import net.codingwell.scalaguice.ScalaModule
import play.api.Play.current

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getBasicUsers(users: Seq[Id[User]]): Future[Map[Id[User],BasicUser]]
  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]]
  def reportArticleSearchResult(res: ArticleSearchResult): Unit
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]]
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Collection]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Collection]]
  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]]
  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]]
  def getBookmarksChanged(seqNum: SequenceNumber, fertchSize: Int): Future[Seq[Bookmark]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]]
  def getCommentsChanged(seqNum: SequenceNumber, fertchSize: Int): Future[Seq[Comment]]
  def getCommentRecipientIds(commentId: Id[Comment]): Future[Seq[Id[User]]]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean]
  def getUserExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]]
}

trait ShoeboxServiceClientModule extends ScalaModule

case class ShoeboxCacheProvider @Inject() (
    userExternalIdCache: UserExternalIdCache,
    uriIdCache: NormalizedURICache,
    clickHistoryCache: ClickHistoryUserIdCache,
    browsingHistoryCache: BrowsingHistoryUserIdCache,
    bookmarkUriUserCache: BookmarkUriUserCache,
    basicUserCache: BasicUserUserIdCache,
    activeSearchConfigExperimentsCache: ActiveExperimentsCache,
    userExperimentCache: UserExperimentCache,
    userConnCache: UserConnectionIdCache,
    externalUserIdCache: ExternalUserIdCache,
    socialUserNetworkCache: SocialUserInfoNetworkCache,
    socialUserCache: SocialUserInfoUserCache,
    userSessionExternalIdCache: UserSessionExternalIdCache)

class ShoeboxServiceClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val port: Int,
  override val httpClient: HttpClient,
  cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient with Logging{

  // request consolidation
  private[this] val consolidateConnectedUsersReq = new RequestConsolidator[UserConnectionKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateClickHistoryReq = new RequestConsolidator[ClickHistoryUserIdKey, Array[Byte]](ttl = 3 seconds)
  private[this] val consolidateBrowsingHistoryReq = new RequestConsolidator[BrowsingHistoryUserIdKey, Array[Byte]](ttl = 3 seconds)
  private[this] val consolidateHasExperimentReq = new RequestConsolidator[(Id[User], State[ExperimentType]), Boolean](ttl = 30 seconds)
  private[this] val consolidateGetExperimentsReq = new RequestConsolidator[String, Seq[SearchConfigExperiment]](ttl = 30 seconds)

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    cacheProvider.userExternalIdCache.getOrElseFutureOpt(UserExternalIdKey(id)) {
      call(Shoebox.internal.getUserOpt(id)).map {r =>
        r.json match {
          case JsNull => None
          case js: JsValue => Some(UserSerializer.userSerializer.reads(js).get)
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

  def getCommentsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Comment]] = {
    call(Shoebox.internal.getCommentsChanged(seqNum.value, fetchSize)).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Comment](js).get)
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

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val query = userIds.mkString(",")
    call(Shoebox.internal.getUsers(query)).map {r =>
      r.json.as[JsArray].value.map(js => UserSerializer.userSerializer.reads(js).get)
    }
  }

  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val (cachedUsers, needToGetUsers) = userIds.map({ u =>
      u -> cacheProvider.externalUserIdCache.getOrElseOpt(ExternalUserIdKey(u))(None)
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
    var cached = Map.empty[Id[User], BasicUser]
    val needed = new ArrayBuffer[Id[User]]
    userIds.foreach{ userId =>
      cacheProvider.basicUserCache.getOrElseOpt(BasicUserUserIdKey(userId))(None) match {
        case Some(bu) => cached += (userId -> bu)
        case None => needed += userId
      }
    }

    if (needed.isEmpty) {
      Promise.successful(cached).future
    } else {
      val query = needed.map(_.id).mkString(",")
      call(Shoebox.internal.getBasicUsers(query)).map { res =>
        val retrievedUsers = res.json.as[Map[String, BasicUser]]
        cached ++ (retrievedUsers.map(u => Id[User](u._1.toLong) -> u._2))
      }
    }
  }

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = consolidateConnectedUsersReq(UserConnectionKey(userId)) { key =>
    cacheProvider.userConnCache.getOrElseFuture(key) {
      call(Shoebox.internal.getConnectedUsers(userId)).map {r =>
        r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
      }
    }
  }

  def reportArticleSearchResult(res: ArticleSearchResult): Unit = {
    call(Shoebox.internal.reportArticleSearchResult, Json.toJson(ArticleSearchResultFactory(res)))
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(Id[NormalizedURI](uriId.id))) {
      call(Shoebox.internal.getNormalizedURI(uriId.id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
    }
  }

  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val query = uriIds.mkString(",")
    call(Shoebox.internal.getNormalizedURIs(query)).map { r =>
      r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }

  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]] = consolidateClickHistoryReq(ClickHistoryUserIdKey(userId)) { key =>
    cacheProvider.clickHistoryCache.get(key) match {
      case Some(clickHistory) => Promise.successful(clickHistory.filter).future
      case None => call(Shoebox.internal.getClickHistoryFilter(userId)).map(_.body.getBytes)
    }
  }

  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]] = consolidateBrowsingHistoryReq(BrowsingHistoryUserIdKey(userId)) { key =>
    cacheProvider.browsingHistoryCache.get(key) match {
      case Some(browsingHistory) => Promise.successful(browsingHistory.filter).future
      case None => call(Shoebox.internal.getBrowsingHistoryFilter(userId)).map(_.body.getBytes)
    }
  }


  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(Shoebox.internal.persistServerSearchEvent, metaData)
  }

  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = {
    call(Shoebox.internal.getPhrasesByPage(page, size)).map { r =>
      r.json.as[JsArray].value.map(jsv => PhraseSerializer.phraseSerializer.reads(jsv).get)
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
        r.json.as[JsArray].value.map { SearchConfigExperimentSerializer.serializer.reads(_).get }
      }
    }
  }
  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    call(Shoebox.internal.getExperiments).map{r =>
      r.json.as[JsArray].value.map{SearchConfigExperimentSerializer.serializer.reads(_).get}
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.getExperiment(id)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(Shoebox.internal.saveExperiment, SearchConfigExperimentSerializer.serializer.writes(experiment)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = consolidateHasExperimentReq((userId, state)) { case (userId, state) =>
    cacheProvider.userExperimentCache.getOrElseOpt(UserExperimentUserIdKey(userId))(None) match {
      case Some(states) => Promise.successful(states.contains(state)).future
      case None => call(Shoebox.internal.hasExperiment(userId, state)).map { r =>
        r.json.as[Boolean]
      }
    }
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
      case Some(states) => Promise.successful(states).future
      case None => call(Shoebox.internal.getUserExperiments(userId)).map { r =>
        r.json.as[Set[String]].map(State[ExperimentType](_)).toSeq
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
    call(Shoebox.internal.getIndexable(seqNum, fetchSize)).map{
      r => r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
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

}

case class ShoeboxServiceClientImplModule() extends ShoeboxServiceClientModule {
  def configure() {}

  @Singleton
  @Provides
  def shoeboxServiceClient (client: HttpClient, cacheProvider: ShoeboxCacheProvider, serviceCluster: ServiceCluster): ShoeboxServiceClient = {
    new ShoeboxServiceClientImpl(
      serviceCluster,
      current.configuration.getInt("service.shoebox.port").get,
      client, cacheProvider)
  }

}
