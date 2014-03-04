package com.keepit.common.actor

import com.keepit.common.akka.FortyTwoActor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import akka.actor.{Cancellable, Scheduler}
import scala.concurrent.duration.{FiniteDuration, Duration}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.reflect._
import com.keepit.common.healthcheck.AirbrakeNotifier

object FlushEventQueueAndClose

trait BatchingActorConfiguration[A <: BatchingActor[_]] {
  val MaxBatchSize: Int
  val LowWatermarkBatchSize: Int
  val MaxBatchFlushInterval: FiniteDuration
  val StaleEventAddTime: Duration
  val StaleEventFlushTime: Duration
}

abstract class BatchingActor[E](airbrake: AirbrakeNotifier)(implicit tag: ClassTag[E]) extends FortyTwoActor(airbrake) {
  protected val clock: Clock
  protected val scheduler: Scheduler
  protected val batchingConf: BatchingActorConfiguration[_ <: BatchingActor[E]]
  protected def getEventTime(event: E): DateTime
  protected def processBatch(events: Seq[E]): Unit
  object FlushEventQueue
  final def flushPlease() = if (!flushIsPending.getAndSet(true)) { self ! FlushEventQueue }

  private val batchId: AtomicInteger = new AtomicInteger(0)
  private var events: Vector[E] = Vector.empty
  private var closing = false
  private var scheduledFlush: Option[Cancellable] = None
  private val flushIsPending = new AtomicBoolean(false)


  def receive = {
    case event: E =>
      log.debug(s"Event added to queue: $event")
      verifyEventStaleTime(event, batchingConf.StaleEventAddTime, "queued")
      events = events :+ event

      if (scheduledFlush.isEmpty && !flushIsPending.get) {
        scheduledFlush = Some(scheduler.scheduleOnce(batchingConf.MaxBatchFlushInterval) { flushPlease() })
      }

      if (closing) {
        flush()
      } else {
        events.size match {
          case s if s >= batchingConf.MaxBatchSize =>
            flush() //flushing without taking in account events in the mailbox
          case s if s >= batchingConf.LowWatermarkBatchSize && !flushIsPending.getAndSet(true) =>
            flushPlease() //flush with the events in the actor mailbox
          case _ =>
            //ignore
        }
      }
    case FlushEventQueueAndClose =>
      closing = true
      flushPlease()
    case FlushEventQueue =>
      flush()
      flushIsPending.set(false)
  }

  private def flush(): Unit = {
    scheduledFlush.foreach(_.cancel())
    scheduledFlush = None
    val thisBatchId = batchId.incrementAndGet
    log.info(s"Processing ${events.size} events: $events")
    events.zipWithIndex map { case (event, i) => verifyEventStaleTime(event, batchingConf.StaleEventFlushTime, s"flushed (${i+1}/${events.size} in batch #$thisBatchId)") }
    processBatch(events)
    events = Vector.empty
  }

  private def verifyEventStaleTime(event: E, timeout: Duration, action: String): Unit = {
    val timeSinceEventStarted = clock.getMillis - getEventTime(event).getMillis
    if (timeSinceEventStarted > timeout.toMillis) {
      val msg = s"Event started ${timeSinceEventStarted}ms ago but was $action only now (timeout: ${timeout}ms): $event"
      //log.error(msg, new Exception(msg))
      //airbrakeNotifier.notify(msg)
    }
  }
}
