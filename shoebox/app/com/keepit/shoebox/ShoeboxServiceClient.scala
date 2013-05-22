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
import scala.util.Success
import scala.util.Failure
import com.keepit.common.mail.ElectronicMail

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def sendMail(email: ElectronicMail): Future[Boolean]

  def getUsers(ids: Seq[Long]): Future[Seq[User]]
  def getNormalizedURI(id: Long) : Future[NormalizedURI]
  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]]
  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def getBookmark(userId: Long): Future[Bookmark]
  def getConnectedUsers(id: Long): Future[Set[Id[User]]]
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache)

class ShoeboxServiceClientImpl @Inject() (override val host: String, override val port: Int, override val httpClient: HttpClient, cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient {
  
  def sendMail(email: ElectronicMail): Future[Boolean] = {
    call(routes.ShoeboxController.sendMail(), Json.toJson(email)).map(r => r.body.toBoolean)
  }

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
}

