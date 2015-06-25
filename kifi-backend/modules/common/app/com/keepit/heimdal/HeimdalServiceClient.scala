package com.keepit.heimdal

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.actor.{ FlushEventQueueAndClose, BatchingActor, BatchingActorConfiguration, ActorInstance }
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time.Clock

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, Id }
import org.joda.time.DateTime
import com.kifi.franz.SQSQueue

trait KeepDiscoveryRepoAccess {
  def getPagedKeepDiscoveries(page: Int = 0, size: Int = 50): Future[Seq[KeepDiscovery]]
  def getDiscoveryCount(): Future[Int]
  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]): Future[Seq[URIDiscoveryCount]]
  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIDiscoveryCount]]
  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepDiscoveryCount]]
}

trait ReKeepRepoAccess {
  def getPagedReKeeps(page: Int = 0, size: Int = 50): Future[Seq[ReKeep]]
  def getReKeepCount(): Future[Int]
  def getUriReKeepsWithCountsByKeeper(userId: Id[User]): Future[Seq[URIReKeepCount]]
  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIReKeepCount]]
  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepReKeptCount]]
  def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]): Future[(Int, Int)]
}

trait HeimdalServiceClient extends ServiceClient with KeepDiscoveryRepoAccess with ReKeepRepoAccess {

  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: HeimdalEvent): Unit

  def getMetricData[E <: HeimdalEvent: HeimdalEventCompanion](name: String): Future[JsObject]

  def updateMetrics(): Unit

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit companion: HeimdalEventCompanion[E]): Future[JsArray]

  def getEventDescriptors[E <: HeimdalEvent](implicit companion: HeimdalEventCompanion[E]): Future[Seq[EventDescriptor]]

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit companion: HeimdalEventCompanion[E]): Future[Int]

  def deleteUser(userId: Id[User]): Unit

  def incrementUserProperties(userId: Id[User], increments: (String, Double)*): Unit

  def setUserProperties(userId: Id[User], properties: (String, ContextData)*): Unit

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit

  def getLastDelightedAnswerDate(userId: Id[User]): Future[Option[DateTime]]

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Option[BasicDelightedAnswer]]

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean]

  def getKeepAttributionInfo(userId: Id[User]): Future[UserKeepAttributionInfo]

  def getUserReKeepsByDegree(keepIds: Seq[KeepIdInfo]): Future[Seq[UserReKeepsAcc]]

  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep]): Future[Seq[ReKeepsPerDeg]]

  def updateUserReKeepStats(userId: Id[User]): Future[Unit]

  def updateUsersReKeepStats(userIds: Seq[Id[User]]): Future[Unit]

  def updateAllReKeepStats(): Future[Unit]

  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]]

  def processSearchHitAttribution(hit: SearchHitReport): Future[Unit]

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit]

  def getOwnerLibraryViewStats(ownerId: Id[User]): Future[(Int, Map[Id[Library], Int])]
}

private[heimdal] object HeimdalBatchingConfiguration extends BatchingActorConfiguration[HeimdalClientActor] {
  val MaxBatchSize = 20
  val LowWatermarkBatchSize = 10
  val MaxBatchFlushInterval = 10 seconds
  val StaleEventAddTime = 40 seconds
  val StaleEventFlushTime = MaxBatchFlushInterval + StaleEventAddTime + (2 seconds)
}

class HeimdalClientActor @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    val clock: Clock,
    val scheduler: Scheduler,
    heimdalEventQueue: SQSQueue[Seq[HeimdalEvent]]) extends BatchingActor[HeimdalEvent](airbrakeNotifier) with ServiceClient with Logging {

  private final val serviceType = ServiceType.HEIMDAL
  val serviceCluster = serviceDiscovery.serviceCluster(serviceType)

  val batchingConf = HeimdalBatchingConfiguration
  def processBatch(events: Seq[HeimdalEvent]): Future[_] = heimdalEventQueue.send(events)
  def getEventTime(event: HeimdalEvent): DateTime = event.time
}

class HeimdalServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster,
  actor: ActorInstance[HeimdalClientActor],
  clock: Clock,
  val scheduling: SchedulingProperties)
    extends HeimdalServiceClient with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(30 seconds)
  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))
  val shortTimeout = CallTimeouts(responseTimeout = Some(1000), maxWaitTime = Some(1000), maxJsonParseTime = Some(1000))

  override def onStop() {
    val res = actor.ref ? FlushEventQueueAndClose
    Await.result(res, Duration(5, SECONDS))
    super.onStop()
  }

  def trackEvent(event: HeimdalEvent): Unit = {
    actor.ref ! event
  }

  def getMetricData[E <: HeimdalEvent: HeimdalEventCompanion](name: String): Future[JsObject] = {
    call(Heimdal.internal.getMetricData(implicitly[HeimdalEventCompanion[E]].typeCode, name)).map { response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  def updateMetrics(): Unit = {
    broadcast(Heimdal.internal.updateMetrics())
  }

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit companion: HeimdalEventCompanion[E]): Future[JsArray] = {
    val eventNames = if (events.isEmpty) Seq("all") else events.map(_.name)
    call(Heimdal.internal.getRawEvents(companion.typeCode, eventNames, limit, window)).map { response =>
      Json.parse(response.body).as[JsArray]
    }
  }

  def getEventDescriptors[E <: HeimdalEvent](implicit companion: HeimdalEventCompanion[E]): Future[Seq[EventDescriptor]] =
    call(Heimdal.internal.getEventDescriptors(companion.typeCode)).map { response =>
      Json.parse(response.body).as[JsArray].value.map(EventDescriptor.format.reads(_).get)
    }

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit companion: HeimdalEventCompanion[E]): Future[Int] =
    call(Heimdal.internal.updateEventDescriptor(companion.typeCode), Json.toJson(eventDescriptors)).map { response =>
      Json.parse(response.body).as[JsNumber].value.toInt
    }

  def deleteUser(userId: Id[User]): Unit = call(Heimdal.internal.deleteUser(userId))

  def incrementUserProperties(userId: Id[User], increments: (String, Double)*): Unit = {
    val payload = JsObject(increments.map { case (key, amount) => key -> JsNumber(amount) })
    call(Heimdal.internal.incrementUserProperties(userId), payload)
  }

  def setUserProperties(userId: Id[User], properties: (String, ContextData)*): Unit = {
    val payload = JsObject(properties.map { case (key, value) => key -> Json.toJson(value) })
    call(Heimdal.internal.setUserProperties(userId), payload, callTimeouts = longTimeout)
  }

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit =
    call(Heimdal.internal.setUserAlias(userId: Id[User], externalId: ExternalId[User]), callTimeouts = longTimeout)

  def getLastDelightedAnswerDate(userId: Id[User]): Future[Option[DateTime]] = {
    call(Heimdal.internal.getLastDelightedAnswerDate(userId), callTimeouts = shortTimeout).map { response =>
      Json.parse(response.body).asOpt[DateTime]
    }
  }

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Option[BasicDelightedAnswer]] = {
    call(Heimdal.internal.postDelightedAnswer(), Json.obj(
      "user" -> Json.toJson(userRegistrationInfo),
      "answer" -> Json.toJson(answer)
    )).map { response =>
      val json = Json.parse(response.body)
      json.asOpt[BasicDelightedAnswer] orElse {
        (json \ "error").asOpt[String].map { msg =>
          log.warn(s"Error posting delighted answer $answer for user ${userRegistrationInfo.userId}: $msg")
        }
        None
      }
    }
  }

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean] = {
    call(Heimdal.internal.cancelDelightedSurvey(), Json.obj(
      "user" -> Json.toJson(userRegistrationInfo)
    )).map { response =>
      Json.parse(response.body) match {
        case JsString(s) if s == "success" => true
        case json =>
          (json \ "error").asOpt[String].map { msg =>
            log.warn(s"Error cancelling delighted survey for user ${userRegistrationInfo.userId}: $msg")
          } getOrElse {
            log.warn(s"Error cancelling delighted survey for user ${userRegistrationInfo.userId}")
          }
          false
      }
    }
  }

  def getPagedKeepDiscoveries(page: Int, size: Int): Future[Seq[KeepDiscovery]] = {
    call(Heimdal.internal.getPagedKeepDiscoveries(page, size)) map { r =>
      Json.parse(r.body).as[Seq[KeepDiscovery]]
    }
  }

  def getDiscoveryCount(): Future[Int] = {
    call(Heimdal.internal.getDiscoveryCount) map { r => Json.parse(r.body).as[Int] }
  }

  def getUriDiscoveriesWithCountsByKeeper(userId: Id[User]): Future[Seq[URIDiscoveryCount]] = {
    call(Heimdal.internal.getUriDiscoveriesWithCountsByKeeper(userId)) map { r =>
      Json.parse(r.body).as[Seq[URIDiscoveryCount]]
    }
  }

  def getDiscoveryCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIDiscoveryCount]] = {
    val payload = Json.toJson(uriIds.toSeq)
    call(Heimdal.internal.getDiscoveryCountsByURIs, payload) map { r =>
      Json.parse(r.body).as[Seq[URIDiscoveryCount]]
    }
  }

  def getDiscoveryCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepDiscoveryCount]] = {
    val payload = Json.obj(
      "userId" -> userId,
      "keepIds" -> Json.toJson(keepIds.toSeq)
    )
    call(Heimdal.internal.getDiscoveryCountsByKeepIds, payload) map { r =>
      Json.parse(r.body).as[Seq[KeepDiscoveryCount]]
    }
  }

  def getKeepAttributionInfo(userId: Id[User]): Future[UserKeepAttributionInfo] = {
    call(Heimdal.internal.getKeepAttributionInfo(userId)) map { r =>
      Json.parse(r.body).as[UserKeepAttributionInfo]
    }
  }

  def getPagedReKeeps(page: Int, size: Int): Future[Seq[ReKeep]] = {
    call(Heimdal.internal.getPagedReKeeps(page, size)) map { r =>
      Json.parse(r.body).as[Seq[ReKeep]]
    }
  }

  def getReKeepCount(): Future[Int] = {
    call(Heimdal.internal.getReKeepCount) map { r => Json.parse(r.body).as[Int] }
  }

  def getUriReKeepsWithCountsByKeeper(userId: Id[User]): Future[Seq[URIReKeepCount]] = {
    call(Heimdal.internal.getUriReKeepsWithCountsByKeeper(userId)) map { r =>
      Json.parse(r.body).as[Seq[URIReKeepCount]]
    }
  }

  def getReKeepCountsByURIs(uriIds: Set[Id[NormalizedURI]]): Future[Seq[URIReKeepCount]] = {
    val payload = Json.toJson(uriIds.toSeq)
    call(Heimdal.internal.getReKeepCountsByURIs, payload) map { r =>
      Json.parse(r.body).as[Seq[URIReKeepCount]]
    }
  }

  def getReKeepCountsByKeepIds(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Seq[KeepReKeptCount]] = {
    val payload = Json.obj(
      "userId" -> userId,
      "keepIds" -> keepIds.toSeq
    )
    call(Heimdal.internal.getReKeepCountsByKeepIds, payload) map { r =>
      Json.parse(r.body).as[Seq[KeepReKeptCount]]
    }
  }

  def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]): Future[(Int, Int)] = {
    call(Heimdal.internal.getReKeepCountsByUserUri(userId, uriId)) map { r =>
      val json = r.json
      val rekeepCount = (json \ "rekeepCount").as[Int]
      val rekeepTotalCount = (json \ "rekeepTotalCount").as[Int]
      (rekeepCount, rekeepTotalCount)
    }
  }

  def getUserReKeepsByDegree(keepIds: Seq[KeepIdInfo]): Future[Seq[UserReKeepsAcc]] = {
    call(Heimdal.internal.getUserReKeepsByDegree, Json.toJson(keepIds)) map { r =>
      Json.parse(r.body).as[Seq[UserReKeepsAcc]]
    }
  }

  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep]): Future[Seq[ReKeepsPerDeg]] = {
    call(Heimdal.internal.getReKeepsByDegree(keeperId, keepId)) map { r =>
      Json.parse(r.body).as[Seq[ReKeepsPerDeg]]
    }
  }

  def updateUserReKeepStats(userId: Id[User]): Future[Unit] = {
    val payload = Json.toJson(userId)
    call(Heimdal.internal.updateUserReKeepStats, payload) map { _ => Unit }
  }

  def updateUsersReKeepStats(userIds: Seq[Id[User]]): Future[Unit] = {
    val payload = Json.toJson(userIds)
    call(Heimdal.internal.updateUsersReKeepStats, payload) map { _ => Unit }
  }

  def updateAllReKeepStats(): Future[Unit] = {
    call(Heimdal.internal.updateAllReKeepStats) map { _ => Unit }
  }

  def getHelpRankInfos(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]] = {
    val payload = Json.toJson(uriIds)
    call(Heimdal.internal.getHelpRankInfo, payload, callTimeouts = longTimeout) map { r =>
      r.json.as[Seq[HelpRankInfo]]
    }
  }

  def processSearchHitAttribution(hit: SearchHitReport): Future[Unit] = {
    call(Heimdal.internal.processSearchHitAttribution, Json.toJson(hit)) map { r => Unit }
  }

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = {
    var count = 0
    FutureHelpers.sequentialExec[Seq[Keep], Unit](newKeeps.grouped(50).toSeq) { batch =>
      val payload = Json.obj(
        "userId" -> userId,
        "keeps" -> batch
      )
      call(Heimdal.internal.processKeepAttribution, payload) map { r =>
        count += batch.size
        log.info(s"[processKeepAttribution(userId=$userId)] processed $count keeps")
      }
    }
  }

  def getOwnerLibraryViewStats(ownerId: Id[User]): Future[(Int, Map[Id[Library], Int])] = {
    call(Heimdal.internal.getOwnerLibraryViewStats(ownerId)).map { res =>
      ((res.json \ "cnt").as[Int], (res.json \ "map").as[Map[Id[Library], Int]])
    }
  }
}
