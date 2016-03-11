package com.keepit.eliza

import akka.actor.Scheduler
import com.google.inject.util.Providers
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.common.service.ServiceType
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.discussion.{MessageSource, CrossServiceMessage, Discussion, Message}
import com.keepit.eliza.model._
import com.keepit.model._
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.notify.model.{GroupingNotificationKind, Recipient}
import com.keepit.search.index.message.ThreadContent
import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsObject}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

class FakeElizaServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler, attributionInfo: mutable.Map[Id[NormalizedURI], Seq[Id[User]]] = mutable.HashMap.empty) extends ElizaServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???
  var inbox = List.empty[NotificationEvent]

  var completedNotifications = List.empty[Recipient]

  def areUsersOnline(users: Seq[Id[User]]): Future[Map[Id[User], Boolean]] = Future.successful(Map.empty)

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

  override def sendNotificationEvent(event: NotificationEvent): Future[Unit] = {
    inbox = event +: inbox
    Future.successful(())
  }

  def getParticipantsByThreadExtId(threadExtId: String): Future[Set[Id[User]]] = Future.successful(Set.empty)

  def completeNotification[N <: NotificationEvent, G](kind: GroupingNotificationKind[N, G], params: G, recipient: Recipient): Future[Boolean] = {
    completedNotifications = recipient +: completedNotifications
    Future.successful(true)
  }

  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]], maxMessagesShown: Int): Future[Map[Id[Keep], Discussion]] = Future.successful(Map.empty)
  def getEmailParticipantsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Map[EmailAddress, (Id[User], DateTime)]]] = Future.successful(Map.empty)
  def deleteMessage(msgId: Id[Message]): Future[Unit] = ???
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Future[Unit] = Future.successful(Unit)
  def editMessage(msgId: Id[Message], newText: String): Future[Message] = ???
  def getCrossServiceMessages(msgIds: Set[Id[Message]]): Future[Map[Id[Message],CrossServiceMessage]] = ???
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[Message]], limit: Int): Future[Seq[Message]] = ???
  def getChangedMessagesFromKeeps(keepIds: Set[Id[Keep]], seq: SequenceNumber[Message]): Future[Seq[CrossServiceMessage]] = Future.successful(Seq.empty)
  def getMessageCountsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Int]] = Future.successful(keepIds.map(_ -> 1).toMap)
  def getElizaKeepStream(userId: Id[User], limit: Int, beforeId: Option[Id[Keep]], filter: ElizaFeedFilter) = ???
  def markKeepsAsReadForUser(userId: Id[User], lastSeen: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep],Int]] = ???
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep], source: Option[MessageSource]): Future[Message] = ???
  def keepHasThreadWithAccessToken(keepId: Id[Keep], accessToken: String): Future[Boolean] = Future.successful(true)
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]]): Future[Set[Id[User]]] = ???
  def getMessagesChanged(seqNum: SequenceNumber[Message], fetchSize: Int): Future[Seq[CrossServiceMessage]] = Future.successful(Seq.empty)
  def convertNonUserThreadToUserThread(userId: Id[User], accessToken: String): Future[(Option[EmailAddress], Option[Id[User]])] = Future.successful((None, Some(Id[User](1)))) // should be different userId than arg
}
