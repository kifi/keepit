package com.keepit.shoebox

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.net.HttpClient
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.time._
import com.keepit.controllers.shoebox._
import com.keepit.model._
import com.keepit.serializer._

import play.api.libs.json._

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
  def getBookmarksInCollection(id: Id[Collection]): Future[Seq[Bookmark]]
  def getCollectionsChanged(seqNum: SequenceNumber): Future[Seq[(Id[Collection], Id[User], SequenceNumber)]]
  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]]
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache,
    clickHistoryCache: ClickHistoryUserIdCache,
    browsingHistoryCache: BrowsingHistoryUserIdCache)

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
    cacheProvider.clickHistoryCache.get(ClickHistoryUserIdKey(userId)) match {
      case Some(clickHistory) => Promise.successful(clickHistory.filter).future
      case None => call(routes.ShoeboxController.getClickHistoryFilter(userId)).map(_.body.getBytes)
    }
  }

  def getBrowsingHistoryFilter(userId: Id[User]): Future[Array[Byte]] = {
    cacheProvider.browsingHistoryCache.get(BrowsingHistoryUserIdKey(userId)) match {
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

  def getCollectionsByUser(userId: Id[User]): Future[Seq[Id[Collection]]] = {
    call(routes.ShoeboxController.getCollectionsByUser(userId)).map { r =>
      Json.fromJson[Seq[Long]](r.json).get.map(Id[Collection](_))
    }
  }
}
