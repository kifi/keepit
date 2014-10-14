package com.keepit.abook

import com.google.inject.Inject
import com.keepit.abook.model._
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.queue.RichConnectionUpdateMessage
import com.keepit.common.routes.ABook
import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.social.SocialNetworkType
import com.keepit.typeahead.TypeaheadHit
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsNumber, _ }

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import com.keepit.common.json.EitherFormat

trait ABookServiceClient extends ServiceClient {

  final val serviceType = ServiceType.ABOOK

  def importContacts(userId: Id[User], oauth2Token: OAuth2Token): Future[Try[ABookInfo]] // gmail
  def uploadContacts(userId: Id[User], origin: ABookOriginType, data: JsValue): Future[Try[ABookInfo]] // ios (see MobileUserController)
  def formUpload(userId: Id[User], json: JsValue): Future[JsValue]
  def getAllABookInfos(): Future[Seq[ABookInfo]]
  def getPagedABookInfos(page: Int, size: Int): Future[Seq[ABookInfo]]
  def getABooksCount(): Future[Int]
  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]]
  def getABookInfo(userId: Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]]
  def getABookInfoByExternalId(id: ExternalId[ABookInfo]): Future[Option[ABookInfo]]
  def getEContactCount(userId: Id[User]): Future[Int]
  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]]
  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]): Future[Option[OAuth2Token]]
  def refreshPrefixFilter(userId: Id[User]): Future[Unit]
  def refreshPrefixFiltersByIds(userIds: Seq[Id[User]]): Future[Unit]
  def refreshAllFilters(): Future[Unit]
  def richConnectionUpdate(message: RichConnectionUpdateMessage): Future[Unit]
  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]]
  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int]
  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]]
  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean]
  def getContactNameByEmail(userId: Id[User], email: EmailAddress): Future[Option[String]]
  def internKifiContacts(userId: Id[User], contacts: BasicContact*): Future[Seq[RichContact]]
  def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int] = None): Future[Seq[TypeaheadHit[RichContact]]]
  def getContactsByUser(userId: Id[User], page: Int = 0, pageSize: Option[Int] = None): Future[Seq[RichContact]]
  def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccountInfo], fetchSize: Int): Future[Seq[EmailAccountInfo]]
  def getContactsChanged(seqNum: SequenceNumber[IngestableContact], fetchSize: Int): Future[Seq[IngestableContact]]
  def getUsersWithContact(email: EmailAddress): Future[Set[Id[User]]]
  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int, bePatient: Boolean = false): Future[Option[Seq[Id[User]]]]
  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]): Future[Unit]
  def getInviteRecommendations(userId: Id[User], offset: Int, limit: Int, networks: Set[SocialNetworkType]): Future[Seq[InviteRecommendation]]
  def hideInviteRecommendation(userId: Id[User], network: SocialNetworkType, irrelevantFriendId: Either[EmailAddress, Id[SocialUserInfo]]): Future[Unit]
  def getIrrelevantPeople(userId: Id[User]): Future[IrrelevantPeople]
}

class ABookServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient, // todo(ray/eng): revisit handling of non-200 responses in service calls
  val serviceCluster: ServiceCluster)
    extends ABookServiceClient with Logging {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxJsonParseTime = Some(30000))

  def importContacts(userId: Id[User], oauth2Token: OAuth2Token): Future[Try[ABookInfo]] = {
    call(ABook.internal.importContacts(userId), Json.toJson(oauth2Token), callTimeouts = longTimeout).map { r =>
      r.status match {
        case Status.OK => Success(Json.fromJson[ABookInfo](r.json).get)
        case _ => Failure(new IllegalArgumentException((r.json \ "code").asOpt[String].getOrElse("invalid arguments")))
      }
    }
  }

  def formUpload(userId: Id[User], json: JsValue): Future[JsValue] = {
    call(ABook.internal.formUpload(userId), json).map { r => r.json }
  }

  def getABookInfo(userId: Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]] = {
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

  def getPagedABookInfos(page: Int, size: Int): Future[Seq[ABookInfo]] = {
    call(ABook.internal.getPagedABookInfos(page, size)).map { r =>
      Json.fromJson[Seq[ABookInfo]](r.json).get
    }
  }

  def getABooksCount(): Future[Int] = {
    call(ABook.internal.getABooksCount()).map { r =>
      Json.fromJson[Int](r.json).get
    }
  }

  def getABookInfoByExternalId(id: ExternalId[ABookInfo]): Future[Option[ABookInfo]] = {
    call(ABook.internal.getABookInfoByExternalId(id)).map { r =>
      Json.fromJson[Option[ABookInfo]](r.json).get
    }
  }

  def getEContactCount(userId: Id[User]): Future[Int] = {
    call(ABook.internal.getEContactCount(userId)).map { r =>
      Json.fromJson[Int](r.json).get
    }
  }

  def getContactNameByEmail(userId: Id[User], email: EmailAddress): Future[Option[String]] = {
    call(ABook.internal.getContactNameByEmail(userId), Json.toJson(email), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Option[String]](r.json).get
    }
  }

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = {
    call(ABook.internal.getABookRawInfos(userId), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Seq[ABookRawInfo]](r.json).get
    }
  }

  def uploadContacts(userId: Id[User], origin: ABookOriginType, data: JsValue): Future[Try[ABookInfo]] = {
    call(ABook.internal.uploadContacts(userId, origin), data).map { r =>
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

  def internKifiContacts(userId: Id[User], contacts: BasicContact*): Future[Seq[RichContact]] = {
    if (contacts.isEmpty) Future.successful(Seq.empty) else call(ABook.internal.internKifiContacts(userId), Json.toJson(contacts)).map { r =>
      r.json.as[Seq[RichContact]]
    }
  }

  def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int]): Future[Seq[TypeaheadHit[RichContact]]] = {
    call(ABook.internal.prefixQuery(userId, query, maxHits)).map { r =>
      r.json.as[Seq[TypeaheadHit[RichContact]]]
    }
  }

  def getContactsByUser(userId: Id[User], page: Int, pageSize: Option[Int]): Future[Seq[RichContact]] = {
    call(ABook.internal.getContactsByUser(userId, page, pageSize)).map { r =>
      r.json.as[Seq[RichContact]]
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

  def richConnectionUpdate(message: RichConnectionUpdateMessage): Future[Unit] = {
    callLeader(ABook.internal.richConnectionUpdate, Json.toJson(message)).map { r => () }
  }

  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]] = {
    implicit val idFormatter = Id.format[SocialUserInfo]
    call(ABook.internal.ripestFruit(userId, howMany)).map { r =>
      r.json.as[Seq[Id[SocialUserInfo]]]
    }
  }

  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int] = {
    call(ABook.internal.countInvitationsSent(userId, friend)).map(_.json.as[Int])
  }

  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]] = {
    call(ABook.internal.getRipestFruits(userId, page, pageSize)).map(_.json.as[Seq[RichSocialConnection]])
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = {
    call(ABook.internal.hideEmailFromUser(userId, email)).map(_.json.as[Boolean])
  }

  def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccountInfo], fetchSize: Int): Future[Seq[EmailAccountInfo]] = {
    call(ABook.internal.getEmailAccountsChanged(seqNum, fetchSize)).map(_.json.as[Seq[EmailAccountInfo]])
  }

  def getContactsChanged(seqNum: SequenceNumber[IngestableContact], fetchSize: Int): Future[Seq[IngestableContact]] = {
    call(ABook.internal.getContactsChanged(seqNum, fetchSize)).map(_.json.as[Seq[IngestableContact]])
  }

  def getUsersWithContact(email: EmailAddress): Future[Set[Id[User]]] =
    call(ABook.internal.getUsersWithContact(email)).map(_.json.as[Set[Id[User]]])

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int, bePatient: Boolean): Future[Option[Seq[Id[User]]]] = {
    call(ABook.internal.getFriendRecommendations(userId, offset, limit, bePatient)).map(_.json.as[Option[Seq[Id[User]]]])
  }

  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]): Future[Unit] = {
    call(ABook.internal.hideFriendRecommendation(userId, irrelevantUserId)).map(_ => ())
  }

  def getInviteRecommendations(userId: Id[User], offset: Int, limit: Int, networks: Set[SocialNetworkType]): Future[Seq[InviteRecommendation]] = {
    call(ABook.internal.getInviteRecommendations(userId, offset, limit, networks)).map(_.json.as[Seq[InviteRecommendation]])
  }

  def hideInviteRecommendation(userId: Id[User], network: SocialNetworkType, irrelevantFriendId: Either[EmailAddress, Id[SocialUserInfo]]) = {
    implicit val irrelevantFriendIdFormat = EitherFormat[EmailAddress, Id[SocialUserInfo]]
    val payload = Json.obj("network" -> network, "irrelevantFriendId" -> irrelevantFriendId)
    call(ABook.internal.hideInviteRecommendation(userId), payload).map(_ => ())
  }

  def getIrrelevantPeople(userId: Id[User]): Future[IrrelevantPeople] = {
    call(ABook.internal.getIrrelevantPeople(userId)).map(_.json.as[IrrelevantPeople])
  }
}
