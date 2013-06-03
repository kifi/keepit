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
  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]]
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
  def getSocialUserInfosByUserId(userId: Id[User]): Future[List[SocialUserInfo]]
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
    extends ShoeboxServiceClient {

  // request consolidation
  private[this] val consolidateConnectedUsersReq = new RequestConsolidator[UserConnectionKey, Set[Id[User]]](ttl = 3 seconds)
  private[this] val consolidateClickHistoryReq = new RequestConsolidator[ClickHistoryUserIdKey, Array[Byte]](ttl = 3 seconds)
  private[this] val consolidateBrowsingHistoryReq = new RequestConsolidator[BrowsingHistoryUserIdKey, Array[Byte]](ttl = 3 seconds)

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
    cacheProvider.userExternalIdCache.get(UserExternalIdKey(id)) match {
      case Some(user) => Promise.successful(Some(user)).future
      case None => call(routes.ShoeboxController.getUserOpt(id)).map{ r =>
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
  
  def getSocialUserInfosByUserId(userId: Id[User]): Future[List[SocialUserInfo]] = {
    cacheProvider.socialUserCache.get(SocialUserInfoUserKey(userId)) match {
      case Some(sui) => Promise.successful(sui).future
      case None => call(routes.ShoeboxController.getSocialUserInfosByUserId(userId)) map { resp =>
        Json.fromJson[List[SocialUserInfo]](resp.json).get
      }
    }
  }


  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarks(userId)).map{ r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Bookmark](js).get)
    }
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = {
    cacheProvider.bookmarkUriUserCache.get(BookmarkUriUserKey(uriId, userId)) match {
      case Some(bookmark) => Promise.successful(Some(bookmark)).future
      case None =>
        call(routes.ShoeboxController.getBookmarkByUriAndUser(uriId, userId)).map { r =>
          Json.fromJson[Option[Bookmark]](r.json).get
        }
    }
  }

  def getPersonalSearchInfo(userId: Id[User], resultSet: ArticleSearchResult): Future[(Map[Id[User], BasicUser], Seq[PersonalSearchHit])] = {
    val allUsers = resultSet.hits.map(_.users).flatten.distinct

    val (preCachedUsers, neededUsers) = allUsers.foldRight((Map[Id[User], BasicUser](), Set[Id[User]]())) { (uid, resSet) =>
      cacheProvider.basicUserCache.get(BasicUserUserIdKey(uid)) match {
        case Some(bu) => (resSet._1 + (uid -> bu), resSet._2)
        case None => (resSet._1, resSet._2 + uid)
      }
    }

    if(neededUsers.nonEmpty || resultSet.hits.nonEmpty) {
      val neededUsersReq = neededUsers.map(_.id).mkString(",")
      val formattedHits = resultSet.hits.map( hit => (if(hit.isMyBookmark) 1 else 0) + ":" + hit.uriId ).mkString(",")

      call(routes.ShoeboxController.getPersonalSearchInfo(userId, neededUsersReq, formattedHits)).map{ res =>
        val personalSearchHits = (Json.fromJson[Seq[PersonalSearchHit]](res.json \ "personalSearchHits")).getOrElse(Seq())
        val neededUsers = (res.json \ "users").as[Map[String, BasicUser]]
        val allUsers = neededUsers.map( b => Id[User](b._1.toLong) -> b._2) ++ preCachedUsers

        (allUsers, personalSearchHits)
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
      u -> cacheProvider.externalUserIdCache.get(ExternalUserIdKey(u))
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
    cacheProvider.userConnCache.get(key) match {
      case Some(conns) => Promise.successful(conns).future
      case None =>
        call(routes.ShoeboxController.getConnectedUsers(userId)).map {r =>
          r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
        }
    }
  }

  def reportArticleSearchResult(res: ArticleSearchResult): Unit = {
    call(routes.ShoeboxController.reportArticleSearchResult, Json.toJson(ArticleSearchResultFactory(res)))
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.get(NormalizedURIKey(Id[NormalizedURI](uriId.id))) match {
      case Some(uri) =>  promise[NormalizedURI]().success(uri).future
      case None => call(routes.ShoeboxController.getNormalizedURI(uriId.id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
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

  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    import com.keepit.controllers.shoebox.ShoeboxController.collectionTupleFormat
    call(routes.ShoeboxController.getCollectionsChanged(seqNum.value)) map { r =>
      Json.fromJson[Seq[(Id[Collection], Id[User], SequenceNumber)]](r.json).get
    }
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    call(routes.ShoeboxController.getBookmarksInCollection(collectionId)) map { r =>
      Json.fromJson[Seq[Bookmark]](r.json).get
    }
  }

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    cacheProvider.activeSearchConfigExperimentsCache.get(ActiveExperimentsKey) match {
      case Some(exps) => Promise.successful(exps).future
      case None => call(routes.ShoeboxController.getActiveExperiments).map { r =>
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
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = {
    cacheProvider.userExperimentCache.get(UserExperimentUserIdKey(userId)) match {
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
