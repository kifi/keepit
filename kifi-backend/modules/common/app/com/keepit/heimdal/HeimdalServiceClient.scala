package com.keepit.heimdal

import com.keepit.common.mail.EmailAddress
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
import com.keepit.model.{ KeepDiscovery, Keep, DelightedUserRegistrationInfo, User }
import com.keepit.common.db.{ ExternalId, Id }
import org.joda.time.DateTime
import com.kifi.franz.SQSQueue

trait HeimdalServiceClient extends ServiceClient {
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

  def getPagedKeepDiscoveries(page: Int = 0, size: Int = 20): Future[Seq[KeepDiscovery]]

  def processKifiHit(clicker: Id[User], hit: SanitizedKifiHit): Future[Unit]

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit]
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
    call(Heimdal.internal.getLastDelightedAnswerDate(userId)).map { response =>
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

  def processKifiHit(clickerId: Id[User], hit: SanitizedKifiHit): Future[Unit] = {
    val payload = Json.obj(
      "clickerId" -> clickerId,
      "kifiHit" -> hit
    )
    call(Heimdal.internal.processKifiHit, payload) map { r => Unit }
  }

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = {
    val payload = Json.obj(
      "userId" -> userId,
      "keeps" -> newKeeps
    )
    call(Heimdal.internal.processKeepAttribution, payload) map { r => Unit }
  }

}
