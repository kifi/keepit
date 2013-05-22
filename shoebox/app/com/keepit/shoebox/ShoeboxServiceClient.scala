package com.keepit.shoebox

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.net.HttpClient
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.{Future, promise}
import com.keepit.controllers.shoebox._
import com.keepit.controllers.shoebox.ShoeboxController
import com.keepit.serializer._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import com.google.inject.Inject
import scala.util.Success
import scala.util.Failure
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.JsObject
import com.keepit.common.db.slick.Database
import com.keepit.search.MultiHashFilter
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import scala.concurrent.Promise

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def sendMail(email: ElectronicMail): Future[Boolean]
  def getBookmarks(userId: Id[User]): Future[Bookmark]
  def getUsersChanged(seqNum: SequenceNumber): Future[Seq[(Id[User], SequenceNumber)]]
  def persistServerSearchEvent(metaData: JsObject): Unit
  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]]
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]]
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache)

class ShoeboxServiceClientImpl @Inject() (
  override val host: String,
  override val port: Int,
  override val httpClient: HttpClient,
  cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient {
  
  def getBookmarks(userId: Id[User]): Future[Bookmark] = {
    ???
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

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = {
    call(routes.ShoeboxController.getConnectedUsers(userId)).map {r =>
      r.json.as[JsArray].value.map(jsv => Id[User](jsv.as[Long])).toSet
    }
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
        val id = (json \ "id").asOpt[Long].get
        val seqNum = (json \ "seqNum").asOpt[Long].get
        (Id[User](id), SequenceNumber(seqNum))
      }
    }
  }

  def getClickHistoryFilter(userId: Id[User]): Future[Array[Byte]] = {
    call(routes.ShoeboxController.getClickHistoryFilter(userId)).map(_.body.getBytes)
  }

  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]] = {
    call(routes.ShoeboxController.getBrowsingHistoryFilter(userId)).map(_.body.getBytes)
  }

  def persistServerSearchEvent(metaData: JsObject): Unit ={
     call(routes.ShoeboxController.persistServerSearchEvent, metaData)
  }

  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = {
    call(routes.ShoeboxController.getPhrasesByPage(page, size)).map { r =>
      r.json.as[JsArray].value.map(jsv => PhraseSerializer.phraseSerializer.reads(jsv).get)
    }
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
  def getPhrasesByPage(page: Int, size: Int): Future[Seq[Phrase]] = ???
}
