package com.keepit.shoebox

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import scala.concurrent.duration._
import com.google.inject.Inject
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
import com.keepit.controllers.shoebox._
import com.keepit.model._
import com.keepit.serializer._
import play.api.libs.json._
import com.keepit.search.ArticleSearchResult
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserUserIdCache
import com.keepit.common.social.BasicUserUserIdKey
import com.keepit.controllers.ext.PersonalSearchHit
import com.keepit.search.ActiveExperimentsCache
import com.keepit.search.ActiveExperimentsKey
import com.keepit.common.db.ExternalId
import com.keepit.search.ArticleSearchResultFactory
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.social.SocialNetworkType
import com.keepit.common.social.SocialId
import com.keepit.serializer.SocialUserInfoSerializer.socialUserInfoSerializer
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.ArticleHit
import com.keepit.common.logging.Logging

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
  def getUserOpt(id: ExternalId[User]): Future[Option[User]]
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]]
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]]
  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]]
  def reportArticleSearchResult(res: ArticleSearchResult): Unit
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]]
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]]
  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]]
  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]]
  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]]
  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]]
  def getPersonalSearchInfo(userId: Id[User], resultSet: ArticleSearchResult): Future[(Map[Id[User], BasicUser], Seq[PersonalSearchHit])]
  def getActiveExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiments: Future[Seq[SearchConfigExperiment]]
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment]
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment]
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean]
  def getUserExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]]
  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]]
  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]]
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
    userConnCache: UserConnectionIdCache,
    externalUserIdCache: ExternalUserIdCache,
    socialUserNetworkCache: SocialUserInfoNetworkCache,
    socialUserCache: SocialUserInfoUserCache,
    userSessionExternalIdCache: UserSessionExternalIdCache)

class ShoeboxServiceClientImpl @Inject() (
  override val host: String,
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
      call(routes.ShoeboxController.getUserOpt(id)).map {r =>
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
      case None => call(routes.ShoeboxController.getSocialUserInfoByNetworkAndSocialId(id.id, networkType.name)) map { resp =>
        Json.fromJson[SocialUserInfo](resp.json).asOpt
      }
    }
  }

  def getSocialUserInfosByUserId(userId: Id[User]): Future[Seq[SocialUserInfo]] = {
    cacheProvider.socialUserCache.get(SocialUserInfoUserKey(userId)) match {
      case Some(sui) => Promise.successful(sui).future
      case None => call(routes.ShoeboxController.getSocialUserInfosByUserId(userId)) map { resp =>
        Json.fromJson[Seq[SocialUserInfo]](resp.json).get
      }
    }
  }


  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarks(userId)).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Bookmark](js).get)
    }
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = {
    cacheProvider.bookmarkUriUserCache.getOrElseFutureOpt(BookmarkUriUserKey(uriId, userId)) {
      call(routes.ShoeboxController.getBookmarkByUriAndUser(uriId, userId)).map { r =>
          Json.fromJson[Option[Bookmark]](r.json).get
      }
    }
  }

  def getPersonalSearchInfo(userId: Id[User], resultSet: ArticleSearchResult): Future[(Map[Id[User], BasicUser], Seq[PersonalSearchHit])] = {
    def splitUserByCache(resultSet: ArticleSearchResult) = {
      val allUsers = resultSet.hits.map(_.users).flatten.distinct

      val (preCachedUsers, neededUsers) = allUsers.foldRight((Map[Id[User], BasicUser](), Set[Id[User]]())) { (uid, resSet) =>
        cacheProvider.basicUserCache.getOrElseOpt(BasicUserUserIdKey(uid))(None) match {
          case Some(bu) => (resSet._1 + (uid -> bu), resSet._2)
          case None => (resSet._1, resSet._2 + uid)
        }
      }
      (preCachedUsers, neededUsers)
    }

    def getPersonalSearchHitFromCache(uriId: Id[NormalizedURI], userId: Id[User], isMyBookmark: Boolean): Option[PersonalSearchHit] = {
      (if (isMyBookmark) cacheProvider.bookmarkUriUserCache.get(BookmarkUriUserKey(uriId, userId)) else None) match {
        case Some(bmk) => {
          val uri = cacheProvider.uriIdCache.get(NormalizedURIKey(uriId))
          if (uri == None) None
          else Some(PersonalSearchHit(uri.get.id.get, uri.get.externalId, bmk.title, bmk.url, bmk.isPrivate))
        }
        case None => None
      }
    }

    def loadCachedBookmarks(userId: Id[User], resultSet: ArticleSearchResult) = {
      val personalHits = new Array[PersonalSearchHit](resultSet.hits.size)
      val indexBuf = ArrayBuffer.empty[Int]
      val hitBuf = ArrayBuffer.empty[ArticleHit]
      for (i <- 0 until resultSet.hits.size) {
        val hit = resultSet.hits(i)
        getPersonalSearchHitFromCache(hit.uriId, userId, hit.isMyBookmark) match {
          case Some(personalHit) => personalHits(i) = personalHit
          case None => { indexBuf.append(i); hitBuf.append(hit) }
        }
      }
      (personalHits, indexBuf, hitBuf)
    }

    val (preCachedUsers, neededUsers) = splitUserByCache(resultSet)
    val (allPersonalHits, indexBuf, hitBuf) = loadCachedBookmarks(userId, resultSet)

    if (neededUsers.nonEmpty || resultSet.hits.nonEmpty) {
      if (neededUsers.size == 0 && hitBuf.size == 0) {
        log.info("getPersonalSearchInfo: everything is cached!")
        Promise.successful((preCachedUsers, allPersonalHits.toSeq)).future
      } else {

        val neededUsersReq = neededUsers.map(_.id).mkString(",")
        val formattedHits = hitBuf.map(hit => (if (hit.isMyBookmark) 1 else 0) + ":" + hit.uriId).mkString(",")

        call(routes.ShoeboxController.getPersonalSearchInfo(userId, neededUsersReq, formattedHits)).map { res =>
          val searchHits = (Json.fromJson[Seq[PersonalSearchHit]](res.json \ "personalSearchHits")).getOrElse(Seq())
          val neededUsers = (res.json \ "users").as[Map[String, BasicUser]]
          val allUsers = neededUsers.map(b => Id[User](b._1.toLong) -> b._2) ++ preCachedUsers

          searchHits.zipWithIndex.foreach { x => allPersonalHits(indexBuf(x._2)) = x._1 }

          (allUsers, allPersonalHits.toSeq)
        }
      }
    } else {
      Promise.successful((Map.empty[Id[User], BasicUser], Nil)).future
    }

  }

  def sendMail(email: ElectronicMail): Future[Boolean] = {
    call(routes.ShoeboxController.sendMail(), Json.toJson(email)).map(r => r.body.toBoolean)
  }

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val query = userIds.mkString(",")
    call(routes.ShoeboxController.getUsers(query)).map {r =>
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
      case users => call(routes.ShoeboxController.getUserIdsByExternalIds(needToGetUsers.mkString(","))).map { r =>
        cachedUsers ++ r.json.as[Seq[Long]].map(Id[User](_))
      }
    }
  }

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = consolidateConnectedUsersReq(UserConnectionKey(userId)) { key =>
    cacheProvider.userConnCache.getOrElseFuture(key) {
      call(routes.ShoeboxController.getConnectedUsers(userId)).map {r =>
        r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
      }
    }
  }

  def reportArticleSearchResult(res: ArticleSearchResult): Unit = {
    call(routes.ShoeboxController.reportArticleSearchResult, Json.toJson(ArticleSearchResultFactory(res)))
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(Id[NormalizedURI](uriId.id))) {
      call(routes.ShoeboxController.getNormalizedURI(uriId.id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
    }
  }

  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
    val query = uriIds.mkString(",")
    call(routes.ShoeboxController.getNormalizedURIs(query)).map { r =>
      r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }

  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]] = {
    call(routes.ShoeboxController.getUsersChanged(seqNum.value)).map{ r =>
      r.json.as[JsArray].value.map{ json =>
        val id = (json \ "id").as[Long]
        val seqNum = (json \ "seqNum").as[Long]
        (Id[User](id), SequenceNumber(seqNum))
      }
    }
  }

  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]] = consolidateClickHistoryReq(ClickHistoryUserIdKey(userId)) { key =>
    cacheProvider.clickHistoryCache.get(key) match {
      case Some(clickHistory) => Promise.successful(clickHistory.filter).future
      case None => call(routes.ShoeboxController.getClickHistoryFilter(userId)).map(_.body.getBytes)
    }
  }

  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]] = consolidateBrowsingHistoryReq(BrowsingHistoryUserIdKey(userId)) { key =>
    cacheProvider.browsingHistoryCache.get(key) match {
      case Some(browsingHistory) => Promise.successful(browsingHistory.filter).future
      case None => call(routes.ShoeboxController.getBrowsingHistoryFilter(userId)).map(_.body.getBytes)
    }
  }


  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(routes.ShoeboxController.persistServerSearchEvent, metaData)
  }

  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = {
    call(routes.ShoeboxController.getPhrasesByPage(page, size)).map { r =>
      r.json.as[JsArray].value.map(jsv => PhraseSerializer.phraseSerializer.reads(jsv).get)
    }
  }

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    import com.keepit.controllers.shoebox.ShoeboxController.collectionTupleFormat
    call(routes.ShoeboxController.getCollectionsChanged(seqNum.value, fetchSize)) map { r =>
      Json.fromJson[Seq[(Id[Collection], Id[User], SequenceNumber)]](r.json).get
    }
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarksInCollection(collectionId)) map { r =>
      Json.fromJson[Seq[Bookmark]](r.json).get
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = consolidateGetExperimentsReq("active") { t =>
    cacheProvider.activeSearchConfigExperimentsCache.getOrElseFuture(ActiveExperimentsKey) {
      call(routes.ShoeboxController.getActiveExperiments).map { r =>
        r.json.as[JsArray].value.map { SearchConfigExperimentSerializer.serializer.reads(_).get }
      }
    }
  }
  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    call(routes.ShoeboxController.getExperiments).map{r =>
      r.json.as[JsArray].value.map{SearchConfigExperimentSerializer.serializer.reads(_).get}
    }
  }
  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    call(routes.ShoeboxController.getExperiment(id)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def saveExperiment(experiment: SearchConfigExperiment): Future[SearchConfigExperiment] = {
    call(routes.ShoeboxController.saveExperiment, SearchConfigExperimentSerializer.serializer.writes(experiment)).map{ r =>
      SearchConfigExperimentSerializer.serializer.reads(r.json).get
    }
  }
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = consolidateHasExperimentReq((userId, state)) { case (userId, state) =>
    cacheProvider.userExperimentCache.getOrElseOpt(UserExperimentUserIdKey(userId))(None) match {
      case Some(states) => Promise.successful(states.contains(state)).future
      case None => call(routes.ShoeboxController.hasExperiment(userId, state)).map { r =>
        r.json.as[Boolean]
      }
    }
  }

  def getUserExperiments(userId: Id[User]): Future[Seq[State[ExperimentType]]] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
      case Some(states) => Promise.successful(states).future
      case None => call(routes.ShoeboxController.getUserExperiments(userId)).map { r =>
        r.json.as[Set[String]].map(State[ExperimentType](_)).toSeq
      }
    }
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    call(routes.ShoeboxController.getCollectionsByUser(userId)).map { r =>
      Json.fromJson[Seq[Long]](r.json).get.map(Id[Collection](_))
    }
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = {
    call(routes.ShoeboxController.getUserIdsByExternalIds(collIds.mkString(","))).map { r =>
      r.json.as[Seq[Long]].map(Id[Collection](_))
    }
  }

  def getIndexable(seqNum: Long, fetchSize: Int): Future[Seq[NormalizedURI]] = {
    call(routes.ShoeboxController.getIndexable(seqNum, fetchSize)).map{
      r => r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }

  def getSessionByExternalId(sessionId: ExternalId[UserSession]): Future[Option[UserSession]] = {
    cacheProvider.userSessionExternalIdCache.get(UserSessionExternalIdKey(sessionId)) match {
      case Some(session) => Promise.successful(Some(session)).future
      case None =>
        call(routes.ShoeboxController.getSessionByExternalId(sessionId)).map { r =>
          r.json match {
            case jso: JsObject => Json.fromJson[UserSession](jso).asOpt
            case _ => None
          }
        }
    }
  }

}
