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


trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]]
  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]]
  def getNormalizedURI(uriId: Id[NormalizedURI]) : Future[NormalizedURI]
  def getNormalizedURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[NormalizedURI]]
  def addBrowsingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def addClickingHistory(userId: Long, uriId: Long, tableSize: Int, numHashFuncs: Int, minHits: Int): Unit
  def getBookmark(userId: Long): Future[Bookmark]
}

case class ShoeboxCacheProvider @Inject() (
    uriIdCache: NormalizedURICache)

class ShoeboxServiceClientImpl @Inject() (override val host: String, override val port: Int, override val httpClient: HttpClient, cacheProvider: ShoeboxCacheProvider)
    extends ShoeboxServiceClient {

  def getUsers(userIds: Seq[Id[User]]): Future[Seq[User]] = {
    val query = userIds.mkString(",")
    call(routes.ShoeboxController.getUsers(query)).map {r =>
      r.json.as[JsArray].value.map(js => UserSerializer.userSerializer.reads(js).get)
    }
  }

  def getConnectedUsers(userId: Id[User]): Future[Set[Id[User]]] = {
    call(routes.ShoeboxController.getConnectedUsers(userId.id)).map {r =>
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

