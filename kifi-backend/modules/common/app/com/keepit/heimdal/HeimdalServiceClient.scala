package com.keepit.heimdal

import java.util.concurrent.atomic.AtomicInteger
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.time.Clock

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import play.api.libs.json.{JsNumber, JsArray, Json, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import com.keepit.serializer.TypeCode
import com.keepit.model.User
import com.keepit.common.db.{ExternalId, Id}

trait HeimdalServiceClient extends ServiceClient {
  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: HeimdalEvent): Unit

  def getMetricData[E <: HeimdalEvent: TypeCode](name: String): Future[JsObject]

  def updateMetrics(): Unit

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit code: TypeCode[E]): Future[JsArray]

  def getEventDescriptors[E <: HeimdalEvent](implicit code: TypeCode[E]): Future[Seq[EventDescriptor]]

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit code: TypeCode[E]): Future[Int]

  def deleteUser(userId: Id[User]): Unit

  def incrementUserProperties(userId: Id[User], increments: (String, Double)*): Unit

  def setUserProperties(userId: Id[User], properties: (String, ContextData)*): Unit

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit
}

object FlushEventQueue
object FlushEventQueueAndClose

private[heimdal] object EventQueueConsts extends Logging {
  val MaxBatchSize = 500
  val LowWatermarkBatchSize = 10
  val BatchFlushTiming = 10 //seconds
  val StaleEventAddTime = 40000 //milli
  val StaleEventFlushTime = (BatchFlushTiming * 1000) + StaleEventAddTime + 2000 //milli
  val batchId = new AtomicInteger(0)

  def verifyEventStaleTime(airbrakeNotifier: AirbrakeNotifier, clock: Clock, event: HeimdalEvent, timeout: Long, message: String): Unit = {
    val timeSinceEventStarted = clock.getMillis - event.time.getMillis
    if (timeSinceEventStarted > timeout) {
      val msg = s"Event started ${timeSinceEventStarted}ms ago but was $message only now (timeout: ${timeout}ms): $event"
      log.error(msg, new Exception(msg))
      //airbrakeNotifier.notify(msg)
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
  private var flushIsPending = false

  def receive = {
    case event: HeimdalEvent =>
      log.debug(s"Event added to queue: $event")
      EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventAddTime, "added")
      events = events :+ event
      if (closing) {
        flush()
      } else {
        events.size match {
          case s if s >= EventQueueConsts.MaxBatchSize =>
            flush() //flushing without taking in account events in the mailbox
          case s if s >= EventQueueConsts.LowWatermarkBatchSize && !flushIsPending =>
            flushIsPending = true
            self ! FlushEventQueue //flush with the events in the actor mailbox
          case _ =>
            //ignore
        }
      }
    case FlushEventQueueAndClose =>
      closing = true
      sender ! flush()
    case FlushEventQueue =>
      flush()
    case m =>
      throw new UnsupportedActorMessage(m)
  }

  def flush() = {
    flushIsPending = false
    val batchId = EventQueueConsts.batchId.incrementAndGet
    events.size match {
      case 0 =>
        //ignore
      case 1 =>
        val event = events(0)
        log.info(s"Sending a single event to Heimdal: $event")
        EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventAddTime, s"flush(1/1) #$batchId")
        call(Heimdal.internal.trackEvent, Json.toJson(event))
        events = Vector()
      case more =>
        log.info(s"Sending ${events.size} events to Heimdal: $events")
        events.zipWithIndex map { case (event, i) => EventQueueConsts.verifyEventStaleTime(airbrakeNotifier, clock, event, EventQueueConsts.StaleEventFlushTime, s"flush($i/${events.size} #$batchId)") }
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
    clock: Clock,
    val scheduling: SchedulingProperties)
  extends HeimdalServiceClient with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(30 seconds)
  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  override def onStart(): Unit = {
    scheduleTaskOnAllMachines(actor.system, 1 seconds, EventQueueConsts.BatchFlushTiming seconds, actor.ref, FlushEventQueue)
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

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit code: TypeCode[E]): Future[JsArray] = {
    val eventNames = if (events.isEmpty) Seq("all") else events.map(_.name)
    call(Heimdal.internal.getRawEvents(code.code, eventNames, limit, window)).map { response =>
      Json.parse(response.body).as[JsArray]
    }
  }

  def getEventDescriptors[E <: HeimdalEvent](implicit code: TypeCode[E]): Future[Seq[EventDescriptor]] =
    call(Heimdal.internal.getEventDescriptors(code.code)).map { response =>
      Json.parse(response.body).as[JsArray].value.map(EventDescriptor.format.reads(_).get)
    }

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit code: TypeCode[E]): Future[Int] =
    call(Heimdal.internal.updateEventDescriptor(code.code), Json.toJson(eventDescriptors)).map { response =>
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
}
