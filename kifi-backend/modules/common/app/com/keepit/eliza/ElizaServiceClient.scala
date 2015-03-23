package com.keepit.eliza

import com.keepit.model.{ NormalizedURI, ChangedURI, NotificationCategory, User }
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

sealed case class PushNotificationCategory(name: String)
object PushNotificationCategory {
  val LibraryChanged = PushNotificationCategory("LibraryChanged")
  val PersonaUpdate = PushNotificationCategory("PersonaUpdate")
  implicit val format = Json.format[PushNotificationCategory]
}
case class PushNotificationExperiment(name: String)
object PushNotificationExperiment {
  val Experiment1 = PushNotificationExperiment("Experiment1")
  val Experiment2 = PushNotificationExperiment("Experiment2")
  implicit val format = Json.format[PushNotificationExperiment]
}

trait ElizaServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ELIZA
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit
  def sendToUser(userId: Id[User], data: JsArray): Unit
  def sendToAllUsers(data: JsArray): Unit

  def sendPushNotification(userId: Id[User], message: String, pushNotificationCategory: PushNotificationCategory, pushNotificationExperiment: PushNotificationExperiment): Future[Int]

  def connectedClientCount: Future[Seq[Int]]

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory, extra: Option[JsObject] = None): Future[Id[MessageHandle]]

  def unsendNotification(messageHandle: Id[MessageHandle]): Unit

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]]

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats]

  def getNonUserThreadMuteInfo(publicId: String): Future[Option[(String, Boolean)]]

  def setNonUserThreadMuteState(publicId: String, muted: Boolean): Future[Boolean]

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Seq[Id[User]]]

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]]

  //migration
  def importThread(data: JsObject): Unit

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]]

  def getUnreadNotifications(userId: Id[User], howMany: Int): Future[Seq[UserThreadView]]
}

class ElizaServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster,
  userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache)
    extends ElizaServiceClient with Logging {

  def sendPushNotification(userId: Id[User], message: String, pushNotificationCategory: PushNotificationCategory, pushNotificationExperiment: PushNotificationExperiment): Future[Int] = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "message" -> message, "pushNotificationCategory" -> pushNotificationCategory, "pushNotificationExperiment" -> pushNotificationExperiment)
    call(Eliza.internal.sendPushNotification, payload).map { response =>
      response.body.toInt
    }
  }

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
    Future.sequence(broadcast(Eliza.internal.connectedClientCount).values.toSeq).map { respSeq =>
      respSeq.map { resp => resp.body.toInt }
    }
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory, extra: Option[JsObject]): Future[Id[MessageHandle]] = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj(
      "userIds" -> userIds.toSeq,
      "title" -> title,
      "body" -> body,
      "linkText" -> linkText,
      "linkUrl" -> linkUrl,
      "imageUrl" -> imageUrl,
      "sticky" -> sticky,
      "category" -> category,
      "extra" -> extra
    )
    call(Eliza.internal.sendGlobalNotification, payload).map { response =>
      Id[MessageHandle](response.body.toLong)
    }
  }

  val longTimeout = CallTimeouts(responseTimeout = Some(10000), maxWaitTime = Some(10000), maxJsonParseTime = Some(10000))

  def unsendNotification(messageHandle: Id[MessageHandle]): Unit = {
    //yes, we really want this one to get through. considering pushing the message to SQS later on.
    call(Eliza.internal.unsendNotification(messageHandle), attempts = 6, callTimeouts = longTimeout)
  }

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]] = {
    call(Eliza.internal.getThreadContentForIndexing(sequenceNumber, maxBatchSize), callTimeouts = longTimeout)
      .map { response =>
        val json = Json.parse(response.body).as[JsArray]
        json.value.map(_.as[ThreadContent])
      }
  }

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats] = {
    userThreadStatsForUserIdCache.get(UserThreadStatsForUserIdKey(userId)) map { s => Future.successful(s) } getOrElse {
      call(Eliza.internal.getUserThreadStats(userId)).map { response =>
        Json.parse(response.body).as[UserThreadStats]
      }
    }
  }

  def getNonUserThreadMuteInfo(publicId: String): Future[Option[(String, Boolean)]] = {
    call(Eliza.internal.getNonUserThreadMuteInfo(publicId), callTimeouts = longTimeout).map { response =>
      Json.parse(response.body).asOpt[(String, Boolean)]
    }
  }

  def setNonUserThreadMuteState(publicId: String, muted: Boolean): Future[Boolean] = {
    call(Eliza.internal.setNonUserThreadMuteState(publicId, muted), callTimeouts = longTimeout).map { response =>
      Json.parse(response.body).as[Boolean]
    }
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    call(Eliza.internal.checkUrisDiscussed(userId), body = Json.toJson(uriIds)).map { r =>
      r.json.as[Seq[Boolean]]
    }
  }

  //migration
  def importThread(data: JsObject): Unit = {
    call(Eliza.internal.importThread, data)
  }

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]] = call(Eliza.internal.getRenormalizationSequenceNumber).map(_.json.as(SequenceNumber.format[ChangedURI]))

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Seq[Id[User]]] = {
    call(Eliza.internal.keepAttribution(userId, uriId)).map { response =>
      Json.parse(response.body).as[Seq[Id[User]]]
    }
  }

  def getUnreadNotifications(userId: Id[User], howMany: Int): Future[Seq[UserThreadView]] = {
    call(Eliza.internal.getUnreadNotifications(userId, howMany)).map { response =>
      Json.parse(response.body).as[Seq[UserThreadView]]
    }
  }
}
