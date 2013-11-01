package com.keepit.heimdal

import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}

import scala.concurrent.{Future, Promise, Await}
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import play.api.Plugin
import play.api.libs.json.{JsArray, Json, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

trait HeimdalServiceClient extends ServiceClient with Plugin {
  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: UserEvent): Unit

  def getMetricData(name: String): Future[JsObject]

  def updateMetrics(): Unit
}

object FlushEventQueue
object FlushEventQueueAndClose

object EventQueueSize {
  val MaxBatchSize = 100
  val LowWatermarkBatchSize = 10
}

class HeimdalClientActor @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery
  ) extends FortyTwoActor(airbrakeNotifier) with ServiceClient with Logging {

  private final val serviceType = ServiceType.HEIMDAL
  val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
  private var events: Vector[UserEvent] = Vector()
  private var closing = false

  def receive = {
    case event: UserEvent =>
      events = events :+ event
      if (closing) {
        flush()
      } else {
        events.size match {
          case s if(s >= EventQueueSize.LowWatermarkBatchSize) =>
            self ! FlushEventQueue //flush with the events in the actor mailbox
          case s if(s >= EventQueueSize.MaxBatchSize) =>
            flush() //flushing without taking in account events in the mailbox
        }
      }
    case FlushEventQueueAndClose =>
      closing = true
      flush()
    case FlushEventQueue =>
      flush()
    case m =>
      throw new UnsupportedActorMessage(m)
  }

  def flush() = {
    events.size match {
      case 0 =>
        //ignore
      case 1 =>
        log.info("Sending a single event to Heimdal")
        call(Heimdal.internal.trackEvent, Json.toJson(events(0)))
        events = Vector()
      case more =>
        log.info(s"Sending ${events.size} events to Heimdal")
        call(Heimdal.internal.trackEvents, Json.toJson(events))
        events = Vector()
    }
  }
}

class HeimdalServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster,
    actor: ActorInstance[HeimdalClientActor])
  extends HeimdalServiceClient with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(30 seconds)

  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  override def onStart(): Unit = {
    scheduleTask(actor.system, 1 seconds, 10 seconds, actor.ref, FlushEventQueue)
  }

  override def onStop() {
    val res = actor.ref ? FlushEventQueueAndClose
    Await.result(res, Duration(30, SECONDS))
    super.onStop()
  }

  def trackEvent(event: UserEvent) : Unit = actor.ref ! event

  def getMetricData(name: String): Future[JsObject] = {
    call(Heimdal.internal.getMetricData(name)).map{ response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  def updateMetrics(): Unit = {
    broadcast(Heimdal.internal.updateMetrics())
  }
}
