package com.keepit.abook


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.{ABookInfo => ABookInfo, ABookRawInfo, ABookOriginType, ContactInfo, User}
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsValue, JsArray, Json, JsObject}

import com.google.inject.Inject
import com.keepit.common.routes.ABook

trait ABookServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ABOOK

  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def uploadDirect(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit
  def getABookInfos(userId:Id[User]):Future[Seq[ABookInfo]]
  def getContactInfos(userId:Id[User], maxRows:Int):Future[Seq[ContactInfo]]
  def getABookRawInfos(userId:Id[User]):Future[Seq[ABookRawInfo]]
  def getContactsRawInfo(userId:Id[User], origin:ABookOriginType):Future[Seq[ContactInfo]]
}


class ABookServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster
)
  extends ABookServiceClient with Logging {

  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue] = {
    call(ABook.internal.upload(userId, origin), json).map { r => r.json }
  }

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = {
    call(ABook.internal.uploadDirect(userId, origin), json).map { r => r.json }
  }

  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {
    call(ABook.internal.upload(userId, origin), Json.toJson(contacts))
  }

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getABookInfos(userId)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getContactInfos(userId: Id[User], maxRows: Int): Future[Seq[ContactInfo]] = {
    call(ABook.internal.getContactInfos(userId, maxRows)).map { r =>
      Json.fromJson[Seq[ContactInfo]](r.json).get
    }
  }

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = {
    call(ABook.internal.getABookRawInfos(userId)).map { r =>
      Json.fromJson[Seq[ABookRawInfo]](r.json).get
    }
  }

  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType): Future[Seq[ContactInfo]] = {
    call(ABook.internal.getContactsRawInfo(userId, origin)).map { r =>
      Json.fromJson[Seq[ContactInfo]](r.json).get
    }
  }
}

class FakeABookServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ABookServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def upload(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def upload(userId: Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {}

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getContactInfos(userId: Id[User], maxRows:Int): Future[Seq[ContactInfo]] = ???

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType): Future[Seq[ContactInfo]] = ???
}
