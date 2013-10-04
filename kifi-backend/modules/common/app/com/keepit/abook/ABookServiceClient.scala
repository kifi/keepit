package com.keepit.abook


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.{ABook => ABookInfo, ABookRawInfo, ABookOriginType, ContactInfo, User}
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsArray, Json, JsObject}

import com.google.inject.Inject
import com.keepit.common.routes.ABook

trait ABookServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ABOOK

  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit
  def getABooks(userId:Id[User]):Future[Seq[ABookInfo]]
  def getAllContactsRawInfo(userId:Id[User]):Future[Seq[ABookRawInfo]]
  def getContactsRawInfo(userId:Id[User], origin:ABookOriginType):Future[Seq[ContactInfo]]
}


class ABookServiceClientImpl @Inject() (
  val healthcheck: HealthcheckPlugin,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster
)
  extends ABookServiceClient with Logging {

  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {
    call(ABook.internal.upload(userId, origin), Json.toJson(contacts))
  }

  def getABooks(userId: Id[User]): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getABooksInfo(userId)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getAllContactsRawInfo(userId: Id[User]): Future[Seq[ABookRawInfo]] = {
    call(ABook.internal.getAllContactsRawInfo(userId)).map { r =>
      Json.fromJson[Seq[ABookRawInfo]](r.json).get
    }
  }

  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType): Future[Seq[ContactInfo]] = {
    call(ABook.internal.getContactsRawInfo(userId, origin)).map { r =>
      Json.fromJson[Seq[ContactInfo]](r.json).get
    }
  }
}

class FakeABookServiceClientImpl(val healthcheck: HealthcheckPlugin) extends ABookServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def upload(userId: Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {}

  def getABooks(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getAllContactsRawInfo(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType): Future[Seq[ContactInfo]] = ???
}
