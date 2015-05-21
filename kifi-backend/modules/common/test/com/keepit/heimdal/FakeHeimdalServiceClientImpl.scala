package com.keepit.heimdal

import com.keepit.model._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.service.ServiceType
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.test.{ FakeRepoWithId, FakeServiceClient }
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ Future, Promise }

import play.api.libs.json.{ JsArray, Json, JsObject }

import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler

class FakeKeepDiscoveryRepoAccess(implicit base: FakeRepoWithId[KeepDiscovery]) extends FakeRepoWithId[KeepDiscovery] with KeepDiscoveryRepoAccess {

  def getPagedKeepDiscoveries(page: Int, size: Int): Future[Seq[KeepDiscovery]] = Future.successful { Seq.empty }

  def getDiscoveryCount(): Future[Int] = Future.successful { base.count }

  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]): Future[Seq[URIDiscoveryCount]] = Future.successful[Seq[URIDiscoveryCount]] {
    val items = base.filter(_.keeperId == userId).groupBy(_.uriId).map {
      case (id, rows) =>
        (id, rows.head.keepId, rows.size)
    }.toSeq
    items.sortBy(_._2)(Id.ord[Keep].reverse) map { case (uriId, keepId, count) => URIDiscoveryCount(uriId, count) }
  }

  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIDiscoveryCount]] = Future.successful {
    base.filter(d => uriIds.contains(d.uriId)).groupBy(_.uriId).map { case (id, rows) => URIDiscoveryCount(id, rows.size) }.toSeq
  }

  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepDiscoveryCount]] = Future.successful {
    base.filter(d => d.keeperId == userId && keepIds.contains(d.keepId)).groupBy(_.keepId).map { case (id, rows) => KeepDiscoveryCount(id, rows.size) }.toSeq
  }

}

class FakeReKeepRepoAccess(implicit base: FakeRepoWithId[ReKeep]) extends FakeRepoWithId[ReKeep] with ReKeepRepoAccess {

  def getPagedReKeeps(page: Int, size: Int): Future[Seq[ReKeep]] = Future.successful { Seq.empty }

  def getReKeepCount(): Future[Int] = Future.successful { base.count }

  def getUriReKeepsWithCountsByKeeper(userId: Id[User]): Future[Seq[URIReKeepCount]] = Future.successful {
    val items = base.filter(_.keeperId == userId).groupBy(_.uriId).map {
      case (uriId, rows) =>
        (uriId, rows.head.keepId, rows.size)
    }.toSeq
    items.sortBy(_._2)(Id.ord[Keep].reverse) map { case (uriId, keepId, count) => URIReKeepCount(uriId, count) }
  }

  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepReKeptCount]] = Future.successful {
    base.filter(rk => rk.keeperId == userId && keepIds.contains(rk.keepId)).groupBy(_.keepId).map { case (id, rows) => KeepReKeptCount(id, rows.size) }.toSeq
  }

  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIReKeepCount]] = Future.successful {
    base.filter(rk => uriIds.contains(rk.uriId)).groupBy(_.uriId).map { case (id, rows) => URIReKeepCount(id, rows.size) }.toSeq
  }

  def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]): Future[(Int, Int)] = Future.successful((0, 0))

}

class FakeHeimdalServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends HeimdalServiceClient with FakeServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  implicit lazy val keepDiscoveryRepo = makeRepoWithId[KeepDiscovery]
  implicit lazy val rekeepRepo = makeRepoWithId[ReKeep]

  val keepDiscoveryRepoAccess = new FakeKeepDiscoveryRepoAccess()
  val rekeepRepoAccess = new FakeReKeepRepoAccess()

  val trackedEvents = ArrayBuffer[HeimdalEvent]()
  def eventsRecorded: Int = synchronized(trackedEvents.size)

  def trackEvent(event: HeimdalEvent): Unit = synchronized {
    trackedEvents += event
  }

  def eventCount: Int = eventsRecorded

  def getMetricData[E <: HeimdalEvent: HeimdalEventCompanion](name: String): Future[JsObject] = Promise.successful(Json.obj()).future

  def updateMetrics(): Unit = {}

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit companion: HeimdalEventCompanion[E]): Future[JsArray] = Future.successful(Json.arr())

  def getEventDescriptors[E <: HeimdalEvent](implicit companion: HeimdalEventCompanion[E]): Future[Seq[EventDescriptor]] = Future.successful(Seq.empty)

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit companion: HeimdalEventCompanion[E]): Future[Int] = Future.successful(0)

  def deleteUser(userId: Id[User]): Unit = {}

  def incrementUserProperties(userId: Id[User], increments: (String, Double)*): Unit = {}

  def setUserProperties(userId: Id[User], properties: (String, ContextData)*): Unit = {}

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = {}

  def getLastDelightedAnswerDate(userId: Id[User]): Future[Option[DateTime]] = Future.successful(None)

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Option[BasicDelightedAnswer]] = Future.successful(None)

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean] = Future.successful(true)

  def getKeepAttributionInfo(userId: Id[User]): Future[UserKeepAttributionInfo] = Future.successful(UserKeepAttributionInfo(userId, 0, 0, 0, 0, 0))

  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]] = Future.successful {
    uriIds.map(HelpRankInfo(_, 0, 0))
  }

  def getUserReKeepsByDegree(keepIds: Seq[KeepIdInfo]): Future[Seq[UserReKeepsAcc]] = Future.successful(Seq.empty)

  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep]): Future[Seq[ReKeepsPerDeg]] = Future.successful(Seq.empty)

  def updateUserReKeepStats(userId: Id[User]): Future[Unit] = Future.successful(())

  def updateUsersReKeepStats(userIds: Seq[Id[User]]): Future[Unit] = Future.successful(())

  def updateAllReKeepStats(): Future[Unit] = Future.successful(())

  def processSearchHitAttribution(hit: SearchHitReport): Future[Unit] = Future.successful(())

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = Future.successful(())

  def getPagedKeepDiscoveries(page: Int, size: Int): Future[Seq[KeepDiscovery]] = keepDiscoveryRepoAccess.getPagedKeepDiscoveries(page, size)
  def getDiscoveryCount(): Future[Int] = keepDiscoveryRepoAccess.getDiscoveryCount
  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]): Future[Seq[URIDiscoveryCount]] = keepDiscoveryRepoAccess.getUriDiscoveriesWithCountsByKeeper(userId)
  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIDiscoveryCount]] = keepDiscoveryRepoAccess.getDiscoveryCountsByURIs(uriIds)
  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepDiscoveryCount]] = keepDiscoveryRepoAccess.getDiscoveryCountsByKeepIds(userId, keepIds)

  def getPagedReKeeps(page: Int, size: Int): Future[Seq[ReKeep]] = rekeepRepoAccess.getPagedReKeeps(page, size)
  def getReKeepCount(): Future[Int] = rekeepRepoAccess.getReKeepCount()
  def getUriReKeepsWithCountsByKeeper(userId: Id[User]): Future[Seq[URIReKeepCount]] = rekeepRepoAccess.getUriReKeepsWithCountsByKeeper(userId)
  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepReKeptCount]] = rekeepRepoAccess.getReKeepCountsByKeepIds(userId, keepIds)
  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIReKeepCount]] = rekeepRepoAccess.getReKeepCountsByURIs(uriIds)
  def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]): Future[(Int, Int)] = rekeepRepoAccess.getReKeepCountsByUserUri(userId, uriId)
  def getOwnerLibraryViewStats(ownerId: Id[User]): Future[(Int, Map[Id[Library], Int])] = Future.successful((5, Map(Id[Library](1) -> 5)))
}
