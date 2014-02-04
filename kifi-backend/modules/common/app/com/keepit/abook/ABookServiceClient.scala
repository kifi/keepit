package com.keepit.abook


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{CallTimeouts, HttpClientImpl, HttpClient}
import com.keepit.common.zookeeper.ServiceCluster
import scala.concurrent._

import akka.actor.Scheduler


import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsValue, JsArray, Json, JsObject}

import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.common.routes.ABook
import scala.util.{Success, Failure, Try}
import play.api.http.Status

trait ABookServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ABOOK

  def importContactsP(userId:Id[User], oauth2Token:OAuth2Token):Future[JsValue]
  def importContacts(userId:Id[User], oauth2Token:OAuth2Token):Future[Try[ABookInfo]] // gmail
  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue):Future[Try[ABookInfo]] // ios (see MobileUserController)
  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def uploadDirect(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue]
  def getAllABookInfos():Future[Seq[ABookInfo]]
  def getPagedABookInfos(page:Int, size:Int):Future[Seq[ABookInfo]]
  def getABooksCount():Future[Int]
  def getABookInfos(userId:Id[User]):Future[Seq[ABookInfo]]
  def getABookInfo(userId:Id[User], id:Id[ABookInfo]):Future[Option[ABookInfo]]
  def getContacts(userId:Id[User], maxRows:Int):Future[Seq[Contact]]
  def getEContacts(userId:Id[User], maxRows:Int):Future[Seq[EContact]]
  def getEContactCount(userId:Id[User]):Future[Int]
  def getEContactById(contactId:Id[EContact]):Future[Option[EContact]]
  def getEContactByEmail(userId:Id[User], email:String):Future[Option[EContact]]
  def getABookRawInfos(userId:Id[User]):Future[Seq[ABookRawInfo]]
  def getOAuth2Token(userId:Id[User], abookId:Id[ABookInfo]):Future[Option[OAuth2Token]]
  def getOrCreateEContact(userId:Id[User], email:String, name:Option[String] = None, firstName:Option[String] = None, lastName:Option[String] = None):Future[Try[EContact]]
  def queryEContacts(userId:Id[User], limit:Int, search:Option[String], after:Option[String]):Future[Seq[EContact]]
}


class ABookServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  htpClient: HttpClient,
  val serviceCluster: ServiceCluster
)
  extends ABookServiceClient with Logging {

  val httpClient =
    if (!htpClient.isInstanceOf[HttpClientImpl]) htpClient
    else htpClient.asInstanceOf[HttpClientImpl].copy(silentFail = true) // todo: revisit default behavior

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxJsonParseTime = Some(30000))

  def importContactsP(userId: Id[User], oauth2Token:OAuth2Token): Future[JsValue] = {
    call(ABook.internal.importContactsP(userId), Json.toJson(oauth2Token), callTimeouts = longTimeout).map { r => r.json }
  }

  def importContacts(userId:Id[User], oauth2Token:OAuth2Token): Future[Try[ABookInfo]] = {
    call(ABook.internal.importContacts(userId), Json.toJson(oauth2Token), callTimeouts = longTimeout).map{ r =>
      r.status match {
        case Status.OK => Success(Json.fromJson[ABookInfo](r.json).get)
        case _ => Failure(new IllegalArgumentException((r.json \ "code").asOpt[String].getOrElse("invalid arguments")))
      }
    }
  }

  def upload(userId:Id[User], origin:ABookOriginType, json:JsValue):Future[JsValue] = {
    call(ABook.internal.upload(userId, origin), json).map { r => r.json }
  }

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = {
    call(ABook.internal.uploadDirect(userId, origin), json).map { r => r.json }
  }

  def getABookInfo(userId:Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]] = {
    call(ABook.internal.getABookInfo(userId, id)).map { r =>
      Json.fromJson[Option[ABookInfo]](r.json).get
    }
  }

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getABookInfos(userId)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getAllABookInfos(): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getAllABookInfos()).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getPagedABookInfos(page:Int, size:Int):Future[Seq[ABookInfo]] = {
    call(ABook.internal.getPagedABookInfos(page, size)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getABooksCount():Future[Int] = {
    call(ABook.internal.getABooksCount()).map { r =>
      Json.fromJson[Int](r.json).get
    }
  }

  def getContacts(userId: Id[User], maxRows: Int): Future[Seq[Contact]] = {
    call(ABook.internal.getContacts(userId, maxRows), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[Contact]](r.json).get
    }
  }

  def getEContacts(userId: Id[User], maxRows: Int): Future[Seq[EContact]] = {
    call(ABook.internal.getEContacts(userId, maxRows), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[EContact]](r.json).get
    }
  }

  def getEContactCount(userId: Id[User]): Future[Int] = {
    call(ABook.internal.getEContactCount(userId)).map { r =>
      Json.fromJson[Int](r.json).get
    }
  }

  def getEContactById(contactId: Id[EContact]): Future[Option[EContact]] = {
    call(ABook.internal.getEContactById(contactId)).map { r =>
      Json.fromJson[Option[EContact]](r.json).get
    }
  }

  def getEContactByEmail(userId: Id[User], email: String): Future[Option[EContact]] = {
    call(ABook.internal.getEContactByEmail(userId, email), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Option[EContact]](r.json).get
    }
  }

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = {
    call(ABook.internal.getABookRawInfos(userId), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[ABookRawInfo]](r.json).get
    }
  }

  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue): Future[Try[ABookInfo]] = {
    call(ABook.internal.uploadForUser(userId, origin), data).map{ r =>
      r.status match {
        case Status.OK => Success(Json.fromJson[ABookInfo](r.json).get)
        case _ => Failure(new IllegalArgumentException((r.json \ "code").asOpt[String].getOrElse("invalid arguments")))
      }
    }
  }

  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]): Future[Option[OAuth2Token]] = {
    call(ABook.internal.getOAuth2Token(userId, abookId)).map { r =>
      if (r.json == null) None // TODO: revisit
      else r.json.as[Option[OAuth2Token]]
    }
  }

  def getOrCreateEContact(userId: Id[User], email: String, name: Option[String], firstName: Option[String], lastName: Option[String]): Future[Try[EContact]] = {
    call(ABook.internal.getOrCreateEContact(userId, email, name, firstName, lastName)).map { r =>
      r.status match {
        case Status.OK => Success(r.json.as[EContact])
        case _ => Failure(new IllegalArgumentException(r.body)) // can do better
      }
    }
  }

  def queryEContacts(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = {
    call(ABook.internal.queryEContacts(userId, limit, search, after)).map { r =>
      Json.fromJson[Seq[EContact]](r.json).get
    }
  }
}

class FakeABookServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ABookServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler)

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def importContactsP(userId: Id[User], oauth2Token: OAuth2Token): Future[JsValue] = ???

  def importContacts(userId: Id[User], oauth2Token: OAuth2Token): Future[Try[ABookInfo]] = ???

  def upload(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def uploadDirect(userId: Id[User], origin: ABookOriginType, json: JsValue): Future[JsValue] = ???

  def getABookInfo(userId: Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]] = ???

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getAllABookInfos(): Future[Seq[ABookInfo]] = ???

  def getPagedABookInfos(page: Int, size: Int): Future[Seq[ABookInfo]] = ???

  def getABooksCount(): Future[Int] = ???

  def getContacts(userId: Id[User], maxRows: Int): Future[Seq[Contact]] = ???

  def getEContacts(userId: Id[User], maxRows: Int): Future[Seq[EContact]] = Future.successful(Seq.empty[EContact])

  def getEContactCount(userId: Id[User]): Future[Int] = ???

  def getEContactById(contactId: Id[EContact]): Future[Option[EContact]] = ???

  def getEContactByEmail(userId: Id[User], email: String): Future[Option[EContact]] = ???

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def uploadContacts(userId: Id[User], origin: ABookOriginType, data: JsValue): Future[Try[ABookInfo]] = ???

  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]): Future[Option[OAuth2Token]] = ???

  def getOrCreateEContact(userId: Id[User], email: String, name: Option[String], firstName: Option[String], lastName: Option[String]): Future[Try[EContact]] = ???

  def queryEContacts(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = ???
}
