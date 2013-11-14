package com.keepit.heimdal

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
import com.keepit.common.time.Clock

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import play.api.Plugin
import play.api.libs.json.{JsArray, Json, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import com.keepit.serializer.TypeCode

trait HeimdalServiceClient extends ServiceClient with Plugin {
  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: HeimdalEvent): Unit

  def getMetricData[E <: HeimdalEvent: TypeCode](name: String): Future[JsObject]

  def updateMetrics(): Unit

  def getRawEvents[E <: HeimdalEvent: TypeCode](limit: Int, events: EventType*): Future[JsArray]
}

object FlushEventQueue
object FlushEventQueueAndClose

object EventQueueConsts extends Logging {
  val MaxBatchSize = 100
  val LowWatermarkBatchSize = 10
  val BatchFlushTiming = 10 //seconds
  val StaleEventAddTime = 40000 //milli
  val StaleEventFlushTime = (BatchFlushTiming * 1000) + StaleEventAddTime + 2000 //milli

  def verifyEventStaleTime(airbrakeNotifier: AirbrakeNotifier, clock: Clock, event: HeimdalEvent, timeout: Long, message: String): Unit = {
    val timeSinceEventStarted = clock.getMillis - event.time.getMillis
    if (timeSinceEventStarted > timeout) {
      val msg = s"Event started ${timeSinceEventStarted}ms ago but was $message only now (timeout: ${timeout}ms): $event"
      log.error(msg, new Exception(msg))
      airbrakeNotifier.notify(msg)
    }
  }
}

class HeimdalClientActor @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    clock: Clock
  ) extends FortyTwoActor(airbrakeNotifier) with ServiceClient with Logging {

  private final val serviceType = ServiceType.HEIMDAL
  val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
  private var events: Vector[HeimdalEvent] = Vector()
  private var closing = false

  def receive = {
    case event: HeimdalEvent =>
      log.info(s"Event added to queue: $event")
      EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventAddTime, "added")
      events = events :+ event
      if (closing) {
        flush()
      } else {
        events.size match {
          case s if(s >= EventQueueConsts.LowWatermarkBatchSize) =>
            self ! FlushEventQueue //flush with the events in the actor mailbox
          case s if(s >= EventQueueConsts.MaxBatchSize) =>
            flush() //flushing without taking in account events in the mailbox
          case _ =>
            //ignore
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
        val event = events(0)
        log.info(s"Sending a single event to Heimdal: $event")
        EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventAddTime, "flush")
        call(Heimdal.internal.trackEvent, Json.toJson(event))
        events = Vector()
      case more =>
        log.info(s"Sending ${events.size} events to Heimdal: ${events}")
        events map { event => EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventFlushTime, "flush") }
        call(Heimdal.internal.trackEvents, Json.toJson(events))
        events = Vector()
    }
  }
}

class HeimdalServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster,
    actor: ActorInstance[HeimdalClientActor],
    clock: Clock)
  extends HeimdalServiceClient with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(30 seconds)

  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  override def onStart(): Unit = {
    scheduleTask(actor.system, 1 seconds, EventQueueConsts.BatchFlushTiming seconds, actor.ref, FlushEventQueue)
  }

  override def onStop() {
    val res = actor.ref ? FlushEventQueueAndClose
    Await.result(res, Duration(30, SECONDS))
    super.onStop()
  }

  def trackEvent(event: HeimdalEvent) : Unit = {
    actor.ref ! event
    EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventAddTime, "post to actor")
  }

  def getMetricData[E <: HeimdalEvent: TypeCode](name: String): Future[JsObject] = {
    call(Heimdal.internal.getMetricData(implicitly[TypeCode[E]].code, name)).map{ response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  def updateMetrics(): Unit = {
    broadcast(Heimdal.internal.updateMetrics())
  }

  def getRawEvents[E <: HeimdalEvent: TypeCode](limit: Int, events: EventType*): Future[JsArray] = {
    val eventNames = if (events.isEmpty) Seq("all") else events.map(_.name)
    call(Heimdal.internal.getRawEvents(implicitly[TypeCode[E]].code, eventNames, limit)).map { response =>
      Json.parse(response.body).as[JsArray]
    }
  }
}
