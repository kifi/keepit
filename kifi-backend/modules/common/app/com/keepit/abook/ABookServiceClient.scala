package com.keepit.abook


// import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model._
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{CallTimeouts, HttpClientImpl, HttpClient}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.queue.RichConnectionUpdateMessage
import scala.concurrent._

import akka.actor.Scheduler


import scala.concurrent.{Future, Promise}

import play.api.libs.json._

import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.common.routes.ABook
import scala.util.{Success, Failure, Try}
import play.api.http.Status
import com.keepit.abook.model.RichSocialConnection
import com.keepit.common.mail.{EmailAddress, BasicContact}
import com.keepit.typeahead.TypeaheadHit

case class RichContact(email: EmailAddress, name: Option[String] = None, firstName: Option[String] = None, lastName: Option[String] = None, userId: Option[Id[User]] = None)
object RichContact {
  implicit val format = Json.format[RichContact]
}

trait ABookServiceClient extends ServiceClient {

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  final val serviceType = ServiceType.ABOOK

  def importContacts(userId:Id[User], oauth2Token:OAuth2Token):Future[Try[ABookInfo]] // gmail
  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue):Future[Try[ABookInfo]] // ios (see MobileUserController)
  def formUpload(userId:Id[User], json:JsValue):Future[JsValue]
  def getAllABookInfos():Future[Seq[ABookInfo]]
  def getPagedABookInfos(page:Int, size:Int):Future[Seq[ABookInfo]]
  def getABooksCount():Future[Int]
  def getABookInfos(userId:Id[User]):Future[Seq[ABookInfo]]
  def getABookInfo(userId:Id[User], id:Id[ABookInfo]):Future[Option[ABookInfo]]
  def getABookInfoByExternalId(id: ExternalId[ABookInfo]):Future[Option[ABookInfo]]
  def getEContacts(userId:Id[User], maxRows:Int):Future[Seq[EContact]]
  def getEContactCount(userId:Id[User]):Future[Int]
  def getEContactsByIds(contactIds:Seq[Id[EContact]]):Future[Seq[EContact]]
  def getABookRawInfos(userId:Id[User]):Future[Seq[ABookRawInfo]]
  def getOAuth2Token(userId:Id[User], abookId:Id[ABookInfo]):Future[Option[OAuth2Token]]
  def queryEContacts(userId:Id[User], limit:Int, search:Option[String], after:Option[String]):Future[Seq[EContact]]
  def prefixSearch(userId:Id[User], query:String):Future[Seq[EContact]]
  def prefixQuery(userId:Id[User], limit:Int, search:Option[String], after:Option[String]):Future[Seq[EContact]]
  def refreshPrefixFilter(userId:Id[User]):Future[Unit]
  def refreshPrefixFiltersByIds(userIds:Seq[Id[User]]):Future[Unit]
  def refreshAllFilters():Future[Unit]
  def richConnectionUpdate(message: RichConnectionUpdateMessage): Future[Unit]
  def blockRichConnection(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Unit]
  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]]
  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int]
  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]]
  def validateAllContacts(readOnly: Boolean): Unit
  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean]
  def getContactNameByEmail(userId:Id[User], email: EmailAddress): Future[Option[String]]
  def internKifiContact(userId: Id[User], contact: BasicContact): Future[RichContact]
  def contactTypeahead(userId: Id[User], query: String, maxHits: Option[Int] = None): Future[Seq[TypeaheadHit[RichContact]]]
}


class ABookServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient, // todo(ray/eng): revisit handling of non-200 responses in service calls
  val serviceCluster: ServiceCluster
)
  extends ABookServiceClient with Logging {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxJsonParseTime = Some(30000))

  def importContacts(userId:Id[User], oauth2Token:OAuth2Token): Future[Try[ABookInfo]] = {
    call(ABook.internal.importContacts(userId), Json.toJson(oauth2Token), callTimeouts = longTimeout).map{ r =>
      r.status match {
        case Status.OK => Success(Json.fromJson[ABookInfo](r.json).get)
        case _ => Failure(new IllegalArgumentException((r.json \ "code").asOpt[String].getOrElse("invalid arguments")))
      }
    }
  }

  def formUpload(userId:Id[User], json:JsValue):Future[JsValue] = {
    call(ABook.internal.formUpload(userId), json).map { r => r.json }
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

  def getABookInfoByExternalId(id: ExternalId[ABookInfo]):Future[Option[ABookInfo]] = {
    call(ABook.internal.getABookInfoByExternalId(id)).map { r =>
      Json.fromJson[Option[ABookInfo]](r.json).get
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

  override def getEContactsByIds(contactIds: Seq[Id[EContact]]): Future[Seq[EContact]] = {
    call(ABook.internal.getEContactsByIds(), JsArray(contactIds.map(c => JsNumber(c.id)))).map { r =>
      Json.fromJson[Seq[EContact]](r.json).get
    }
  }

  def getContactNameByEmail(userId:Id[User], email: EmailAddress): Future[Option[String]] = {
    call(ABook.internal.getContactNameByEmail(userId), Json.toJson(email), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Option[String]](r.json).get
    }
  }

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = {
    call(ABook.internal.getABookRawInfos(userId), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[ABookRawInfo]](r.json).get
    }
  }

  def uploadContacts(userId:Id[User], origin:ABookOriginType, data:JsValue): Future[Try[ABookInfo]] = {
    call(ABook.internal.uploadContacts(userId, origin), data).map{ r =>
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

  def internKifiContact(userId:Id[User], contact: BasicContact):Future[RichContact] = {
    call(ABook.internal.internKifiContact(userId), Json.toJson(contact)).map { r =>
      r.json.as[RichContact]
    }
  }

  def queryEContacts(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = {
    call(ABook.internal.queryEContacts(userId, limit, search, after)).map { r =>
      r.status match {
        case Status.OK => Json.fromJson[Seq[EContact]](r.json).get
        case _ => throw new IllegalStateException(s"[queryEContacts($userId,$limit,$search,$after)] failed with ${r.status}; body=${r.body}")
      }
    }
  }

  def prefixSearch(userId: Id[User], query: String): Future[Seq[EContact]] = {
    call(ABook.internal.prefixSearch(userId, query)).map { r =>
      r.status match {
        case Status.OK => Json.fromJson[Seq[EContact]](r.json).get
        case _ => throw new IllegalStateException(s"[prefixSearch($userId,$query)] failed with ${r.status}; body=${r.body}")
      }
    }
  }

  def prefixQuery(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = {
    call(ABook.internal.prefixQuery(userId, limit, search, after)).map { r =>
      r.status match {
        case Status.OK => Json.fromJson[Seq[EContact]](r.json).get
        case _ => throw new IllegalStateException(s"[prefixQuery($userId,$limit,$search,$after)] failed with ${r.status}; body=${r.body}")
      }
    }
  }

  def contactTypeahead(userId: Id[User], query: String, maxHits: Option[Int]): Future[Seq[TypeaheadHit[RichContact]]] = {
    call(ABook.internal.contactTypeahead(userId, query, maxHits)).map { r =>
      r.json.as[Seq[TypeaheadHit[RichContact]]]
    }
  }

  def refreshPrefixFilter(userId: Id[User]): Future[Unit] = {
    call(ABook.internal.refreshPrefixFilter(userId)).map { r =>
      r.status match {
        case Status.OK => Unit
        case _ => throw new IllegalStateException(s"[refreshPrefixFilter($userId) failed with ${r.status}; body=${r.body}")
      }
    }
  }

  def refreshPrefixFiltersByIds(userIds: Seq[Id[User]]): Future[Unit] = {
    call(ABook.internal.refreshPrefixFiltersByIds(), JsArray(userIds.map(u => JsNumber(u.id)))) map { r =>
      r.status match {
        case Status.OK => Unit
        case _ => throw new IllegalStateException(s"[refreshPrefixFiltersByIds(${userIds.length};${userIds.take(50).mkString(",")})] failed with ${r.status}; body=${r.body}")
      }
    }
  }

  override def refreshAllFilters(): Future[Unit] = {
    call(ABook.internal.refreshAllPrefixFilters()).map { r =>
      r.status match {
        case Status.OK => Unit
        case _ => throw new IllegalStateException(s"[refreshAllFilters] failed with ${r.status}; body=${r.body}")
      }
    }
  }

  def richConnectionUpdate(message: RichConnectionUpdateMessage) : Future[Unit] = {
    callLeader(ABook.internal.richConnectionUpdate, Json.toJson(message)).map{r => ()}
  }

  def blockRichConnection(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Unit] = {
    implicit val userIdFormat = Id.format[User]
    implicit val socialIdFormat = Id.format[SocialUserInfo]
    val json = Json.obj("userId" -> userId, "friendSocialId" -> friend.left.toOption, "friendEmailAddress" -> friend.right.toOption)
    call(ABook.internal.blockRichConnection, json).map(_ => ())
  }


  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]] = {
    implicit val idFormatter = Id.format[SocialUserInfo]
    call(ABook.internal.ripestFruit(userId, howMany)).map{ r =>
      r.json.as[Seq[Id[SocialUserInfo]]]
    }
  }

  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int] = {
    call(ABook.internal.countInvitationsSent(userId, friend)).map(_.json.as[Int])
  }

  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]] = {
    call(ABook.internal.getRipestFruits(userId, page, pageSize)).map(_.json.as[Seq[RichSocialConnection]])
  }

  def validateAllContacts(readOnly: Boolean): Unit = {
    call(ABook.internal.validateAllContacts(readOnly))
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = {
    call(ABook.internal.hideEmailFromUser(userId, email)).map(_.json.as[Boolean])
  }

}

class FakeABookServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ABookServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, ()=>{})

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def importContacts(userId: Id[User], oauth2Token: OAuth2Token): Future[Try[ABookInfo]] = ???

  def formUpload(userId: Id[User], json: JsValue): Future[JsValue] = ???

  def getABookInfo(userId: Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]] = ???

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getAllABookInfos(): Future[Seq[ABookInfo]] = ???

  def getPagedABookInfos(page: Int, size: Int): Future[Seq[ABookInfo]] = ???

  def getABookInfoByExternalId(id: ExternalId[ABookInfo]):Future[Option[ABookInfo]] = ???

  def getABooksCount(): Future[Int] = ???

  def getEContacts(userId: Id[User], maxRows: Int): Future[Seq[EContact]] = Future.successful(Seq.empty[EContact])

  def getEContactCount(userId: Id[User]): Future[Int] = ???

  def getEContactsByIds(contactIds: Seq[Id[EContact]]): Future[Seq[EContact]] = ???

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def uploadContacts(userId: Id[User], origin: ABookOriginType, data: JsValue): Future[Try[ABookInfo]] = ???

  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]): Future[Option[OAuth2Token]] = ???

  def queryEContacts(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = ???

  def prefixSearch(userId: Id[User], query: String): Future[Seq[EContact]] = ???

  def prefixQuery(userId: Id[User], limit: Int, search: Option[String], after: Option[String]): Future[Seq[EContact]] = ???

  def refreshPrefixFilter(userId: Id[User]): Future[Unit] = ???

  def refreshPrefixFiltersByIds(userIds: Seq[Id[User]]): Future[Unit] = ???

  def refreshAllFilters(): Future[Unit] = ???

  def richConnectionUpdate(message: RichConnectionUpdateMessage) : Future[Unit] =  ???

  def blockRichConnection(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Unit] = ???

  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]] = ???

  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int] = ???

  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]] = ???

  def validateAllContacts(readOnly: Boolean = true): Unit = ???

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = ???

  def getContactNameByEmail(userId:Id[User], email: EmailAddress): Future[Option[String]] = Future.successful(None)

  def internKifiContact(userId: Id[User], contact: BasicContact): Future[RichContact] = ???

  def contactTypeahead(userId: Id[User], query: String, maxHits: Option[Int]): Future[Seq[TypeaheadHit[RichContact]]] = Future.successful(Seq.empty)

}
