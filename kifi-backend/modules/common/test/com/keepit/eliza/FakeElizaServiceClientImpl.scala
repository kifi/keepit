package com.keepit.eliza

import com.keepit.discussion.{Discussion, Message}
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, SequenceNumber, Id }
import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Eliza
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.notify.model.{Recipient, GroupingNotificationKind}
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.search.index.message.ThreadContent
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }

import play.api.libs.json.{ JsString, JsValue, JsArray, Json, JsObject }

import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.eliza.model._

import akka.actor.Scheduler
import com.keepit.common.json.TupleFormat._

class FakeElizaServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler, attributionInfo: mutable.Map[Id[NormalizedURI], Seq[Id[User]]] = mutable.HashMap.empty) extends ElizaServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???
  var inbox = List.empty[NotificationEvent]

  var completedNotifications = List.empty[Recipient]

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray) = Future.successful((): Unit)
  def sendUserPushNotification(userId: Id[User], message: String, recipient: User, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int] = Future.successful(1)
  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean): Future[Int] = Future.successful(1)
  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean): Future[Int] = Future.successful(1)
  def sendOrgPushNotification(orgPushNotificationRequest: OrgPushNotificationRequest): Future[Int] = Future.successful(1)

  def sendToUser(userId: Id[User], data: JsArray) = Future.successful((): Unit)

  def sendToAllUsers(data: JsArray): Unit = {}

  def flush(userId: Id[User]): Future[Unit] = Future.successful((): Unit)

  def connectedClientCount: Future[Seq[Int]] = {
    val p = Promise.successful(Seq[Int](1))
    p.future
  }

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]] = {
    val p = Promise.successful(Seq[ThreadContent]())
    p.future
  }

  def getNonUserThreadMuteInfo(publicId: String): Future[Option[(String, Boolean)]] = {
    Promise.successful(Some(("test_id", false))).future
  }

  def setNonUserThreadMuteState(publicId: String, muted: Boolean): Future[Boolean] = {
    Promise.successful(true).future
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    Future.successful(Seq.fill(uriIds.size)(false))
  }

  //migration
  def importThread(data: JsObject): Unit = {}

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats] = Promise.successful(UserThreadStats(0, 0, 0)).future

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]] = Future.successful(SequenceNumber.ZERO)

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Seq[Id[User]]] = {
    Future.successful(attributionInfo.get(uriId).getOrElse(Seq.empty).filter(_ != userId))
  }

  def getUnreadNotifications(userId: Id[User], howMany: Int): Future[Seq[UserThreadView]] = {
    Future.successful(Seq.empty)
  }

  override def getSharedThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]] = Future.successful(Seq.empty)

  override def getAllThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]] = Future.successful(Seq.empty)

  override def getTotalMessageCountForGroup(users: Set[Id[User]]): Future[Int] = Future.successful(0)

  override def sendNotificationEvent(event: NotificationEvent): Future[Unit] = {
    inbox = event +: inbox
    Future.successful(())
  }

  def getParticipantsByThreadExtId(threadExtId: String): Future[Set[Id[User]]] = Future.successful(Set.empty)

  def completeNotification[N <: NotificationEvent, G](kind: GroupingNotificationKind[N, G], params: G, recipient: Recipient): Future[Boolean] = {
    completedNotifications = recipient +: completedNotifications
    Future.successful(true)
  }

  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]] = {
    Future.successful(Map.empty)
  }

}
