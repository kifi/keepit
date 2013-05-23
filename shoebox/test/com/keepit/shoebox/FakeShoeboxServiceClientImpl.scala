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

// code below should be sync with code in ShoeboxController
class FakeShoeboxServiceClientImpl @Inject() (
    cacheProvider: ShoeboxCacheProvider,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    browsingHistoryRepo: BrowsingHistoryRepo,
    clickingHistoryRepo: ClickHistoryRepo,
    normUriRepo: NormalizedURIRepo,
    clickHistoryTracker: ClickHistoryTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    clock: Clock,
    fortyTwoServices: FortyTwoServices
)
    extends ShoeboxServiceClient {
  val host: String = ""
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def getUser(id: Id[User]): Future[User] = {
    //call(routes.ShoeboxController.getUser(id)).map(r => UserSerializer.userSerializer.reads(r.json))
    ???
  }

  def getNormalizedURI(uriId: Id[NormalizedURI]): Future[NormalizedURI] = {
    cacheProvider.uriIdCache.get(NormalizedURIKey(uriId)) match {
      case Some(uri) => promise[NormalizedURI]().success(uri).future
      case None => {
        val uri = db.readOnly { implicit s =>
          normUriRepo.get(uriId)
        }
        promise[NormalizedURI]().success(uri).future
      }
    }
  }


  def getNormalizedURIs(ids: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]] = {
     val uris = db.readOnly { implicit s =>
         ids.map{ id => normUriRepo.get(id)
       }
     }
     promise[Seq[NormalizedURI]]().success(uris).future
  }

  def getBookmarks(userId: Id[User]): Future[Bookmark] = {
    ???
  }

  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]] = {
    val changed = db.readOnly { implicit s =>
      bookmarkRepo.getUsersChanged(seqNum)
    }
    promise[Seq[(Id[User], SequenceNumber)]]().success(changed).future

  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
    //persistEventPlugin.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }

  def getClickHistoryFilter(userId: Id[User]) = {
    Promise.successful(clickHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def getBrowsingHistoryFilter(userId: Id[User]) = {
    Promise.successful(browsingHistoryTracker.getMultiHashFilter(userId).getFilter).future
  }

  def getConnectedUsers(id: Id[User]): scala.concurrent.Future[Set[com.keepit.common.db.Id[com.keepit.model.User]]] = ???
  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = ???
  def sendMail(email: com.keepit.common.mail.ElectronicMail): Future[Boolean] = ???
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = Promise.successful(Seq()).future

  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]] = {
    Promise.successful(Seq()).future
  }

  def getBookmarksInCollection(collectionId: Id[Collection]): Future[Seq[Bookmark]] = {
    Promise.successful(Seq()).future
  }

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    Promise.successful(Seq()).future
  }

  def getIndexable(seqNum: Long, fetchSize: Int) : Future[Seq[NormalizedURI]] = {
    val uris = db.readOnly { implicit s =>
        normUriRepo.getIndexable(SequenceNumber(seqNum), fetchSize)
      }
    Promise.successful(uris).future
  }
}
