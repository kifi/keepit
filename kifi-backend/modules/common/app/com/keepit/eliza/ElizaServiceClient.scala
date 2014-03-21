package com.keepit.eliza

import com.keepit.model.{ChangedURI, NotificationCategory, User}
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Eliza
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.zookeeper.{ServiceInstance, ServiceCluster}
import com.keepit.search.message.ThreadContent
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsArray, Json, JsObject}

import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.eliza.model.{UserThreadStatsForUserIdKey, UserThreadStatsForUserIdCache, UserThreadStats}

import akka.actor.Scheduler

trait ElizaServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ELIZA
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit
  def sendToUser(userId: Id[User], data: JsArray): Unit
  def sendToAllUsers(data: JsArray): Unit

  def connectedClientCount: Future[Seq[Int]]

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory) : Future[Long]

  def unsendNotification(id: Long): Unit

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]]

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats]

  //migration
  def importThread(data: JsObject): Unit

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]]
}


class ElizaServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster,
    userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache
  )
  extends ElizaServiceClient with Logging {

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "data" -> data)
    broadcast(Eliza.internal.sendToUserNoBroadcast, payload)
  }

  def sendToUser(userId: Id[User], data: JsArray): Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "data" -> data)
    call(Eliza.internal.sendToUser, payload)
  }

  def sendToAllUsers(data: JsArray): Unit = {
    broadcast(Eliza.internal.sendToAllUsers, data)
  }

  def connectedClientCount: Future[Seq[Int]] = {
    Future.sequence(broadcast(Eliza.internal.connectedClientCount)).map{ respSeq =>
      respSeq.map{ resp => resp.body.toInt }
    }
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory) : Future[Long] = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj(
      "userIds"   -> userIds.toSeq,
      "title"     -> title,
      "body"      -> body,
      "linkText"  -> linkText,
      "linkUrl"   -> linkUrl,
      "imageUrl"  -> imageUrl,
      "sticky"    -> sticky,
      "category"  -> category
    )
    call(Eliza.internal.sendGlobalNotification, payload).map { response =>
      response.body.toLong
    }
  }

  def unsendNotification(id: Long): Unit = {
    call(Eliza.internal.unsendNotification(id))
  }

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]] = {
    call(Eliza.internal.getThreadContentForIndexing(sequenceNumber, maxBatchSize), callTimeouts = CallTimeouts(responseTimeout = Some(10000), maxWaitTime = Some(10000), maxJsonParseTime = Some(10000)))
      .map{ response =>
        val json = Json.parse(response.body).as[JsArray]
        json.value.map(_.as[ThreadContent])
      }
  }

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats] = {
    userThreadStatsForUserIdCache.get(UserThreadStatsForUserIdKey(userId)) map { s => Future.successful(s) } getOrElse {
      call(Eliza.internal.getUserThreadStats(userId)).map{ response =>
        Json.parse(response.body).as[UserThreadStats]
      }
    }
  }

  //migration
  def importThread(data: JsObject): Unit = {
    call(Eliza.internal.importThread, data)
  }

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]] = call(Eliza.internal.getRenormalizationSequenceNumber).map(_.json.as(SequenceNumber.format[ChangedURI]))
}

class FakeElizaServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ElizaServiceClient{
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, ()=>{})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {}

  def sendToUser(userId: Id[User], data: JsArray): Unit = {}

  def sendToAllUsers(data: JsArray): Unit = {}

  def connectedClientCount: Future[Seq[Int]] = {
    val p = Promise.successful(Seq[Int](1))
    p.future
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory) : Future[Long] = {
    val p = Promise.successful(42.toLong)
    p.future
  }

  def unsendNotification(id: Long): Unit = {}

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]] = {
    val p = Promise.successful(Seq[ThreadContent]())
    p.future
  }

  //migration
  def importThread(data: JsObject): Unit = {}

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats] = Promise.successful(UserThreadStats(0, 0, 0)).future

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]] = Future.successful(SequenceNumber.ZERO)
}
