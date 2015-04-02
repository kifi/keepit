package com.keepit.eliza

import com.keepit.model._
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Eliza
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.search.index.message.ThreadContent
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }

import play.api.libs.json.{ JsString, JsValue, JsArray, Json, JsObject }

import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.eliza.model.{ UserThreadView, MessageHandle, UserThreadStatsForUserIdKey, UserThreadStatsForUserIdCache, UserThreadStats }

import akka.actor.Scheduler
import com.keepit.common.json.TupleFormat._

class FakeElizaServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler, attributionInfo: mutable.Map[Id[NormalizedURI], Seq[Id[User]]] = mutable.HashMap.empty) extends ElizaServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???
  var inbox = List.empty[(Id[User], NotificationCategory, String, String)]

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {}
  def sendUserPushNotification(userId: Id[User], message: String, recipient: User, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int] = Future.successful(1)
  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory): Future[Int] = Future.successful(1)
  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory): Future[Int] = Future.successful(1)

  def sendToUser(userId: Id[User], data: JsArray): Unit = {}

  def sendToAllUsers(data: JsArray): Unit = {}

  def connectedClientCount: Future[Seq[Int]] = {
    val p = Promise.successful(Seq[Int](1))
    p.future
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory, unread: Boolean = true, extra: Option[JsObject]): Future[Id[MessageHandle]] = {
    userIds.map { id =>
      inbox = (id, category, linkUrl, imageUrl) +: inbox
    }
    val p = Promise.successful(Id[MessageHandle](42.toLong))
    p.future
  }

  var unsentNotificationIds = List[Id[MessageHandle]]()

  def unsendNotification(messageHandle: Id[MessageHandle]): Unit = {
    unsentNotificationIds = messageHandle :: unsentNotificationIds
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
}
