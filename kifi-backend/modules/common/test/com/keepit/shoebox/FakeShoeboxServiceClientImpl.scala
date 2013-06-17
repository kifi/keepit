package com.keepit.shoebox

import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import scala.concurrent.{Future, Promise, promise}
import com.google.inject.Inject
import play.api.libs.json.JsObject
import com.keepit.serializer.NormalizedURISerializer
import com.keepit.search.SearchConfigExperiment
import com.keepit.search.SearchConfigExperimentRepo
import com.keepit.serializer.SearchConfigExperimentSerializer
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.search.PersonalSearchHit
import com.keepit.search.ArticleSearchResult
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworkType

// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl @Inject() (
    cacheProvider: ShoeboxCacheProvider,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    bookmarkRepo: BookmarkRepo,
    browsingHistoryRepo: BrowsingHistoryRepo,
    clickingHistoryRepo: ClickHistoryRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    normUriRepo: NormalizedURIRepo,
    experimentRepo: SearchConfigExperimentRepo,
    userExperimentRepo: UserExperimentRepo,
    clickHistoryTracker: ClickHistoryTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    clock: Clock,
    fortyTwoServices: FortyTwoServices
)
    extends ShoeboxServiceClient {
  val host: String = ""
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def getUserOpt(id: ExternalId[User]): Future[Option[User]] = {
     val userOpt =  db.readOnly { implicit s => userRepo.getOpt(id) }
     Promise.successful(userOpt).future
  }

  def getUser(id: Id[User]): Future[User] = {
    //call(routes.ShoeboxController.getUser(id)).map(r => UserSerializer.userSerializer.reads(r.json))
    ???
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI] = {
    cacheProvider.uriIdCache.getOrElseFuture(NormalizedURIKey(uriId)) {
      val uri = db.readOnly { implicit s =>
          normUriRepo.get(uriId)
        }
      Promise.successful(uri).future
    }
  }


  def getNormalizedURIs(ids: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
     val uris = db.readOnly { implicit s =>
         ids.map{ id => normUriRepo.get(id)
       }
     }
     promise[Seq[NormalizedURI]]().success(uris).future
  }

  def getBookmarks(userId: Id[User]): Future[Seq[Bookmark]] = {
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId)
    }
    Promise.successful(bookmarks).future
  }

  def getBookmarksChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[Bookmark]] = {
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getBookmarksChanged(seqNum, fetchSize)
    }
    Promise.successful(bookmarks).future
  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
    //EventPersister.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }

  def getClickHistoryFilter(userId: Id[User]) = {
    Promise.successful(clickHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def getBrowsingHistoryFilter(userId: Id[User]) = {
    Promise.successful(browsingHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def setConnections(connections: Map[Id[User], Set[Id[User]]]) {
    connectionsOpt = Some(connections)
  }

  private[this] var connectionsOpt: Option[Map[Id[User], Set[Id[User]]]] = None

  def getConnectedUsers(id: Id[User]): scala.concurrent.Future[Set[com.keepit.common.db.Id[com.keepit.model.User]]] = {
    val connections = connectionsOpt.getOrElse(???)
    Promise.successful(connections.getOrElse(id, Set()) - id).future
  }

  def reportArticleSearchResult(res: ArticleSearchResult): Unit = {}
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = ???
  def getUserIdsByExternalIds(userIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = ???

  def getBasicUsers(userIds: Seq[Id[User]]): Future[Map[Id[User],BasicUser]] = {
    val users = db.readOnly { implicit s =>
      userIds.map{ userId => userId -> basicUserRepo.load(userId) }.toMap
    }
    Promise.successful(users).future
  }

  def sendMail(email: com.keepit.common.mail.ElectronicMail): Future[Boolean] = ???
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = Promise.successful(Seq()).future
  def getSocialUserInfoByNetworkAndSocialId(id: SocialId, networkType: SocialNetworkType): Future[Option[SocialUserInfo]] = ???
  def getSessionByExternalId(sessionId: com.keepit.common.db.ExternalId[com.keepit.model.UserSession]): scala.concurrent.Future[Option[com.keepit.model.UserSession]] = ???
  def getSocialUserInfosByUserId(userId: com.keepit.common.db.Id[com.keepit.model.User]): scala.concurrent.Future[List[com.keepit.model.SocialUserInfo]] = ???
  def getUserExperiments(userId: com.keepit.common.db.Id[com.keepit.model.User]): scala.concurrent.Future[Seq[com.keepit.common.db.State[com.keepit.model.ExperimentType]]] = ???

  def getCollectionsChanged(seqNum: SequenceNumber, fetchSize: Int): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    val colls = db.readOnly { implicit s =>
        collectionRepo.getCollectionsChanged(seqNum, fetchSize)
      }
    Promise.successful(colls).future
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    val bookmarks = db.readOnly { implicit s =>
        keepToCollectionRepo.getBookmarksInCollection(collectionId) map bookmarkRepo.get
      }
    Promise.successful(bookmarks).future
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    Promise.successful(Seq()).future
  }

  def getCollectionIdsByExternalIds(collIds: Seq[ExternalId[Collection]]): Future[Seq[Id[Collection]]] = ???

  def getIndexable(seqNum: Long, fetchSize: Int) : Future[Seq[NormalizedURI]] = {
    val uris = db.readOnly { implicit s =>
        normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
      }
    Promise.successful(uris).future
  }

  def getBookmarkByUriAndUser(uriId: Id[NormalizedURI], userId: Id[User]): Future[Option[Bookmark]] = ???

  def getActiveExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = db.readOnly { implicit s => experimentRepo.getActive() }
    Promise.successful(exp).future
  }

  def getExperiments: Future[Seq[SearchConfigExperiment]] = {
    val exp = db.readOnly { implicit s => experimentRepo.getNotInactive() }
    Promise.successful(exp).future
  }

  def getExperiment(id: Id[SearchConfigExperiment]): Future[SearchConfigExperiment] = {
    val exp = db.readOnly { implicit s => experimentRepo.get(id) }
    Promise.successful(exp).future
  }
  def saveExperiment(experiment: SearchConfigExperiment) = {
    val saved = db.readWrite { implicit s => experimentRepo.save(experiment) }
    Promise.successful(saved).future
  }
  def hasExperiment(userId: Id[User], state: State[ExperimentType]): Future[Boolean] = {
     val has = db.readOnly { implicit s =>
       userExperimentRepo.hasExperiment(userId, state)
     }
     Promise.successful(has).future
  }
}
