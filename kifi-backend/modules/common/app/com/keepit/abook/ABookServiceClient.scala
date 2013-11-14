package com.keepit.abook


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model._
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

  def importContacts(userId:Id[User], provider:String, accessToken:String):Future[JsValue]
  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def uploadDirect(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit
  def getABookInfos(userId:Id[User]):Future[Seq[ABookInfo]]
  def getABookInfo(id:Id[ABookInfo]):Future[Option[ABookInfo]]
  def getContacts(userId:Id[User], maxRows:Int):Future[Seq[Contact]]
  def getEContacts(userId:Id[User], maxRows:Int):Future[Seq[EContact]]
  def getEContactById(contactId:Id[EContact]):Future[Option[EContact]]
  def getEContactByEmail(userId:Id[User], email:String):Future[Option[EContact]]
  def getContactInfos(userId:Id[User], maxRows:Int):Future[Seq[ContactInfo]]
  def getABookRawInfos(userId:Id[User]):Future[Seq[ABookRawInfo]]
  def getContactsRawInfo(userId:Id[User], origin:ABookOriginType):Future[Seq[ContactInfo]]
  def getMergedContactInfos(userId:Id[User], maxRows:Int):Future[JsArray]
  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue):Future[JsValue]
}


class ABookServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster
)
  extends ABookServiceClient with Logging {

  def importContacts(userId: Id[User], provider: String, accessToken: String): Future[JsValue] = {
    call(ABook.internal.importContacts(userId, provider, accessToken)).map { r => r.json }
  }

  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue] = {
    call(ABook.internal.upload(userId, origin), json).map { r => r.json }
  }

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = {
    call(ABook.internal.uploadDirect(userId, origin), json).map { r => r.json }
  }

  def upload(userId:Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {
    call(ABook.internal.upload(userId, origin), Json.toJson(contacts))
  }

  def getABookInfo(id: Id[ABookInfo]): Future[Option[ABookInfo]] = {
    call(ABook.internal.getABookInfo(id)).map { r =>
      Json.fromJson[Option[ABookInfo]](r.json).get
    }
  }

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getABookInfos(userId)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getContacts(userId: Id[User], maxRows: Int): Future[Seq[Contact]] = {
    call(ABook.internal.getContacts(userId, maxRows)).map { r =>
      Json.fromJson[Seq[Contact]](r.json).get
    }
  }

  def getEContacts(userId: Id[User], maxRows: Int): Future[Seq[EContact]] = {
    call(ABook.internal.getEContacts(userId, maxRows)).map { r =>
      Json.fromJson[Seq[EContact]](r.json).get
    }
  }

  def getEContactById(contactId: Id[EContact]): Future[Option[EContact]] = {
    call(ABook.internal.getEContactById(contactId)).map { r =>
      Json.fromJson[Option[EContact]](r.json).get
    }
  }

  def getEContactByEmail(userId: Id[User], email: String): Future[Option[EContact]] = {
    call(ABook.internal.getEContactByEmail(userId, email)).map { r =>
      Json.fromJson[Option[EContact]](r.json).get
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

  def getMergedContactInfos(userId: Id[User], maxRows: Int): Future[JsArray] = {
    call(ABook.internal.getMergedContactInfo(userId, maxRows)).map { r =>
      Json.fromJson[JsArray](r.json).get
    }
  }

  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue): Future[JsValue] = {
    call(ABook.internal.uploadForUser(userId, origin), data).map{ r =>
      r.json
    }
  }
}

class FakeABookServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ABookServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def importContacts(userId: Id[User], provider: String, accessToken: String): Future[JsValue] = ???

  def upload(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def upload(userId: Id[User], origin:ABookOriginType, contacts:Seq[ContactInfo]):Unit = {}

  def getABookInfo(id: Id[ABookInfo]): Future[Option[ABookInfo]] = ???

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getContacts(userId: Id[User], maxRows: Int): Future[Seq[Contact]] = ???

  def getEContacts(userId: Id[User], maxRows: Int): Future[Seq[EContact]] = ???

  def getEContactById(contactId: Id[EContact]): Future[Option[EContact]] = ???

  def getEContactByEmail(userId: Id[User], email: String): Future[Option[EContact]] = ???

  def getContactInfos(userId: Id[User], maxRows:Int): Future[Seq[ContactInfo]] = ???

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType): Future[Seq[ContactInfo]] = ???

  def getMergedContactInfos(userId: Id[User], maxRows: Int): Future[JsArray] = ???

  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue): Future[JsValue] = ???

}
