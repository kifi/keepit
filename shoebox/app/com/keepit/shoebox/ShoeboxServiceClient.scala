package com.keepit.shoebox

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.net.HttpClient
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.{Future, promise}
import com.keepit.controllers.shoebox._
import com.keepit.controllers.shoebox.ShoeboxController
import com.keepit.serializer._
import play.api.libs.json.{JsArray, JsValue, Json}
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.JsNumber
import play.api.libs.json.JsNull
import play.api.libs.json.JsValue
import play.api.mvc.Action
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import com.google.inject.Inject
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.JsObject
import com.keepit.common.db.slick.Database
import com.keepit.search.MultiHashFilter
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUsers(ids: Seq[Long]): Future[Seq[User]]
  def getNormalizedURI(id: Long) : Future[NormalizedURI]
  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]]
  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def getBookmark(userId: Long): Future[Bookmark]
  def getConnectedUsers(id: Long): Future[Set[Id[User]]]
  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]]
  def persistServerSearchEvent(metaData: JsObject): Unit
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache)

class ShoeboxServiceClientImpl @Inject() (override val host: String, override val port: Int, override val httpClient: HttpClient, cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient {

  def getUsers(ids: Seq[Long]): Future[Seq[User]] = {
    val idJarray = JsArray(ids.map(JsNumber(_)) )
    call(routes.ShoeboxController.getUsers, idJarray).map {r =>
      r.json.as[JsArray].value.map(js => UserSerializer.userSerializer.reads(js).get)
    }
  }

  def getConnectedUsers(id: Long): Future[Set[Id[User]]] = {
    call(routes.ShoeboxController.getConnectedUsers(id)).map {r =>
      r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
    }
  }

  def getNormalizedURI(id: Long) : Future[NormalizedURI] = {
    cacheProvider.uriIdCache.get(NormalizedURIKey(Id[NormalizedURI](id))) match {
      case Some(uri) =>  promise[NormalizedURI]().success(uri).future
      case None => call(routes.ShoeboxController.getNormalizedURI(id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
    }
  }

  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]] = {
    val idJarray = JsArray(ids.map(JsNumber(_)))
    call(routes.ShoeboxController.getNormalizedURIs, idJarray).map { r =>
      r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }

  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit = {
      call(routes.ShoeboxController.addBrowsingHistory(userId, uriId, tableSize, numHashFuncs, minHits))
  }

  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit = {
    call(routes.ShoeboxController.addClickingHistory(userId, uriId, tableSize, numHashFuncs, minHits))
  }

  def getBookmark(userId: Long): Future[Bookmark] = {
    ???
  }

  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]] = {
    call(routes.ShoeboxController.getUsersChanged(seqNum.value)).map{ r =>
      r.json.as[JsArray].value.map{ json =>
        val id = (json \ "id").asOpt[Long].get
        val seqNum = (json \ "seqNum").asOpt[Long].get
        (Id[User](id), SequenceNumber(seqNum))
      }
    }
  }

  def getClickHistoryMultiHashFilter(userId: Id[User]) = {

  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(routes.ShoeboxController.persistServerSearchEvent, metaData)
  }

}

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
    persistEventPlugin: PersistEventPlugin,
    clock: Clock,
    fortyTwoServices: FortyTwoServices
)
    extends ShoeboxServiceClient {
  val host: String = ???
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def getUser(id: Id[User]): Future[User] = {
    //call(routes.ShoeboxController.getUser(id)).map(r => UserSerializer.userSerializer.reads(r.json))
    ???
  }

  def getNormalizedURI(id: Long): Future[NormalizedURI] = {
    cacheProvider.uriIdCache.get(NormalizedURIKey(Id[NormalizedURI](id))) match {
      case Some(uri) => promise[NormalizedURI]().success(uri).future
      case None => {
        val uri = db.readOnly { implicit s =>
          normUriRepo.get(Id[NormalizedURI](id))
        }
        promise[NormalizedURI]().success(uri).future
      }
    }
  }


  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]] = {
     val uris = db.readOnly { implicit s =>
         ids.map{ id => normUriRepo.get(Id[NormalizedURI](id))
       }
     }
     promise[Seq[NormalizedURI]]().success(uris).future
  }

  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit = {
    def getMultiHashFilter(userId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = {
      db.readOnly { implicit session =>
        browsingHistoryRepo.getByUserId(Id[User](userId)) match {
          case Some(browsingHistory) =>
            new MultiHashFilter(browsingHistory.tableSize, browsingHistory.filter, browsingHistory.numHashFuncs, browsingHistory.minHits)
          case None =>
            val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
            filter
        }
      }
    }

    val filter = getMultiHashFilter(userId, tableSize, numHashFuncs, minHits)
    filter.put(uriId)

    db.readWrite { implicit session =>
      browsingHistoryRepo.save(browsingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          BrowsingHistory(userId = Id[User](userId), tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }

  }

  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit = {
    def getMultiHashFilter(userId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int) = {
      db.readOnly { implicit session =>
        clickingHistoryRepo.getByUserId(Id[User](userId)) match {
          case Some(clickingHistory) =>
            new MultiHashFilter(clickingHistory.tableSize, clickingHistory.filter, clickingHistory.numHashFuncs, clickingHistory.minHits)
          case None =>
            val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
            filter
        }
      }
    }

    val filter = getMultiHashFilter(userId, tableSize, numHashFuncs, minHits)
    filter.put(uriId)

    db.readWrite { implicit session =>
      clickingHistoryRepo.save(clickingHistoryRepo.getByUserId(Id[User](userId)) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          ClickHistory(userId = Id[User](userId), tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }

  }

  def getBookmark(userId: Long): Future[Bookmark] = {
    ???
  }

  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]] = {
    val changed = db.readOnly { implicit s =>
      bookmarkRepo.getUsersChanged(seqNum)
    }
    promise[Seq[(Id[User], SequenceNumber)]]().success(changed).future

  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
    persistEventPlugin.persist(Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_return_hits", metaData.as[JsObject])(clock, fortyTwoServices))
  }
}
