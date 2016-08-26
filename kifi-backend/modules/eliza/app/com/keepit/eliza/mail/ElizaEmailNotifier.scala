package com.keepit.eliza.mail

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.eliza.model._
import com.keepit.inject.AppScoped
import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.model.Keep
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.{ Failure, Success, Try }
import scala.concurrent.Future
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import scala.collection.mutable
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ParticipantThreadBatch[T <: ParticipantThread](val participantThreads: Seq[T], val keepId: Id[Keep])
object ParticipantThreadBatch {
  def apply[T <: ParticipantThread](participantThreads: Seq[T]) = {
    require(participantThreads.nonEmpty)
    val keepId = participantThreads.head.keepId
    require(participantThreads.forall(_.keepId == keepId)) // All user threads must correspond to the same keep
    new ParticipantThreadBatch(participantThreads, keepId)
  }
}

object ElizaEmailNotifierActor {
  case class SendNextEmails(maxConcurrentThreadBatches: Int = MAX_CONCURRENT_MESSAGE_THREAD_BATCHES)
  case class DoneWithThread(keepId: Id[Keep], result: Try[Unit], maxConcurrentThreadBatches: Int)
  val ALLOWED_ATTEMPTS = 2
  val MIN_TIME_BETWEEN_NOTIFICATIONS = 15 minutes
  val RECENT_ACTIVITY_WINDOW = 24 hours
  val MAX_CONCURRENT_MESSAGE_THREAD_BATCHES = 1
}

abstract class ElizaEmailNotifierActor[T <: ParticipantThread](airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import ElizaEmailNotifierActor._

  private val participantThreadBatchQueue = new mutable.Queue[ParticipantThreadBatch[T]]()
  private var threadBatchesBeingProcessed = 0
  private val failures = mutable.Map[Id[Keep], Int]().withDefaultValue(0)

  protected def getParticipantThreadsToProcess(): Seq[T]
  protected def emailUnreadMessagesForParticipantThreadBatch(batch: ParticipantThreadBatch[T]): Future[Unit]

  def receive = {

    case SendNextEmails(maxConcurrentThreadBatches) =>
      log.info("Attempting to process next user emails")
      if (threadBatchesBeingProcessed == 0) {
        if (participantThreadBatchQueue.isEmpty) {
          val participantThreadBatches = getParticipantThreadsToProcess().groupBy(_.keepId).values.map(ParticipantThreadBatch(_)).toSeq
          participantThreadBatches.foreach(participantThreadBatchQueue.enqueue(_))
        }

        while (threadBatchesBeingProcessed < maxConcurrentThreadBatches && participantThreadBatchQueue.nonEmpty) {
          log.info(s"userThreadBatchQueue size: ${participantThreadBatchQueue.size}")
          val batch = participantThreadBatchQueue.dequeue()
          val keepId = batch.keepId

          if (failures(keepId) < ALLOWED_ATTEMPTS) {
            emailUnreadMessagesForParticipantThreadBatch(batch).onComplete { result => self ! DoneWithThread(keepId, result, maxConcurrentThreadBatches) }
            threadBatchesBeingProcessed += 1
          }
        }
      }

    case DoneWithThread(keepId, result, maxConcurrentThreadBatches) =>
      threadBatchesBeingProcessed -= 1
      result match {
        case Success(_) => failures -= keepId
        case Failure(ex) =>
          failures(keepId) += 1
          airbrake.notify(s"Failure occurred during user thread batch processing: keepId = ${keepId}}", ex)
      }
      if (threadBatchesBeingProcessed == 0) { self ! SendNextEmails(maxConcurrentThreadBatches) }

    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[ElizaEmailNotifierPluginImpl])
trait ElizaEmailNotifierPlugin extends SchedulerPlugin {
  def sendEmails(): Unit
}

@AppScoped
class ElizaEmailNotifierPluginImpl @Inject() (
  nonUserEmailActor: ActorInstance[ElizaNonUserEmailNotifierActor],
  userEmailActor: ActorInstance[ElizaUserEmailNotifierActor],
  val scheduling: SchedulingProperties)
    extends ElizaEmailNotifierPlugin with Logging {

  import com.keepit.eliza.mail.ElizaEmailNotifierActor.SendNextEmails

  implicit val actorTimeout = Timeout(5 second)

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ElizaEmailNotifierPluginImpl")
    scheduleTaskOnOneMachine(nonUserEmailActor.system, 30 seconds, 10 minutes, nonUserEmailActor.ref, SendNextEmails(), "nonUserEmail")
    scheduleTaskOnOneMachine(userEmailActor.system, 90 seconds, 10 minutes, userEmailActor.ref, SendNextEmails(), "userEmail")
  }

  def sendEmails() {
    nonUserEmailActor.ref ! SendNextEmails()
    userEmailActor.ref ! SendNextEmails()
  }
}
