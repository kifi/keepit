package com.keepit.abook

import akka.actor.Scheduler
import com.google.inject.util.Providers
import com.keepit.abook.model._
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.queue.RichConnectionUpdateMessage
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.social.SocialNetworkType
import com.keepit.typeahead.TypeaheadHit
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

class FakeABookServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ABookServiceClient with Logging {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, () => {})

  val typeaheadHitMap = new collection.mutable.HashMap[Id[User], Seq[TypeaheadHit[RichContact]]]
  def addTypeaheadHits(userId: Id[User], hits: Seq[TypeaheadHit[RichContact]]): Unit = {
    val updated = typeaheadHitMap.get(userId) match {
      case Some(h) => h ++ hits
      case None => hits
    }
    typeaheadHitMap.put(userId, updated)
    log.info(s"[addTypeaheadHits($userId)] map=$typeaheadHitMap")
  }

  // allow test clients to set expectations
  var contactsConnectedToEmailAddress: Set[Id[User]] = Set.empty
  private val friendRecommendationsExpectations = collection.mutable.HashMap[Id[User], Seq[Id[User]]]()

  def addFriendRecommendationsExpectations(userId: Id[User], recoUserIds: Seq[Id[User]]) = synchronized {
    friendRecommendationsExpectations(userId) = recoUserIds
  }

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def importContacts(userId: Id[User], oauth2Token: OAuth2Token): Future[Try[ABookInfo]] = ???

  def formUpload(userId: Id[User], json: JsValue): Future[JsValue] = ???

  def getABookInfo(userId: Id[User], id: Id[ABookInfo]): Future[Option[ABookInfo]] = ???

  def getABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = ???

  def getAllABookInfos(): Future[Seq[ABookInfo]] = ???

  def getPagedABookInfos(page: Int, size: Int): Future[Seq[ABookInfo]] = ???

  def getABookInfoByExternalId(id: ExternalId[ABookInfo]): Future[Option[ABookInfo]] = ???

  def getABooksCount(): Future[Int] = ???

  def getEContactCount(userId: Id[User]): Future[Int] = ???

  def getABookRawInfos(userId: Id[User]): Future[Seq[ABookRawInfo]] = ???

  def uploadContacts(userId: Id[User], origin: ABookOriginType, data: JsValue): Future[Try[ABookInfo]] = ???

  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]): Future[Option[OAuth2Token]] = ???

  def refreshPrefixFilter(userId: Id[User]): Future[Unit] = ???

  def refreshPrefixFiltersByIds(userIds: Seq[Id[User]]): Future[Unit] = ???

  def refreshAllFilters(): Future[Unit] = ???

  def richConnectionUpdate(message: RichConnectionUpdateMessage): Future[Unit] = ???

  def blockRichConnection(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Unit] = ???

  def ripestFruit(userId: Id[User], howMany: Int): Future[Seq[Id[SocialUserInfo]]] = ???

  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Future[Int] = ???

  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int): Future[Seq[RichSocialConnection]] = ???

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = ???

  def getContactNameByEmail(userId: Id[User], email: EmailAddress): Future[Option[String]] = Future.successful(None)

  def internKifiContact(userId: Id[User], contact: BasicContact): Future[RichContact] = ???

  def prefixQuery(userId: Id[User], query: String, maxHits: Option[Int]): Future[Seq[TypeaheadHit[RichContact]]] = Future.successful {
    typeaheadHitMap.get(userId) match {
      case None => Seq.empty
      case Some(hits) =>
        val filtered = hits.filter(_.name.startsWith(query))
        log.info(s"prefixQuery($userId,$query,$maxHits)=$filtered")
        maxHits match {
          case Some(max) => filtered.take(max)
          case _ => filtered
        }
    }
  }

  def getContactsByUser(userId: Id[User], page: Int, pageSize: Option[Int]): Future[Seq[RichContact]] = Future.successful(Seq.empty)

  def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccountInfo], fetchSize: Int): Future[Seq[EmailAccountInfo]] = Future.successful(Seq.empty)

  def getContactsChanged(seqNum: SequenceNumber[IngestableContact], fetchSize: Int): Future[Seq[IngestableContact]] = Future.successful(Seq.empty)

  def getUsersWithContact(email: EmailAddress): Future[Set[Id[User]]] = Future.successful(contactsConnectedToEmailAddress)

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Option[Seq[Id[User]]]] = {
    Future.successful(Some(friendRecommendationsExpectations(userId)))
  }

  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]): Future[Unit] = Future.successful(())

  def getInviteRecommendations(userId: Id[User], page: Int, pageSize: Int, relevantNetworks: Set[SocialNetworkType]): Future[Seq[InviteRecommendation]] = Future.successful(Seq.empty)

  def hideInviteRecommendation(userId: Id[User], network: SocialNetworkType, irrelevantFriendId: Either[EmailAddress, Id[SocialUserInfo]]) = Future.successful(())

  def getIrrelevantRecommendations(userId: Id[User]) = Future.successful(IrrelevantPeopleRecommendations.empty(userId))

}
