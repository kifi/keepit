package com.keepit.eliza.mail

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.eliza.model._
import com.keepit.inject.AppScoped
import com.google.inject.{ Inject, ImplementedBy }
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.{ Failure, Success, Try }
import scala.concurrent.Future
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import scala.collection.mutable
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ParticipantThreadBatch[T <: ParticipantThread](val participantThreads: Seq[T], val threadId: Id[MessageThread])
object ParticipantThreadBatch {
  def apply[T <: ParticipantThread](participantThreads: Seq[T]) = {
    require(participantThreads.nonEmpty)
    val threadId = participantThreads.head.threadId
    require(participantThreads.forall(_.threadId == threadId)) // All user threads must correspond to the same MessageThread
    new ParticipantThreadBatch(participantThreads, threadId)
  }
}

object ElizaEmailNotifierActor {
  case class SendNextEmails(maxConcurrentThreadBatches: Int = MAX_CONCURRENT_MESSAGE_THREAD_BATCHES)
  case class DoneWithThread(threadId: Id[MessageThread], result: Try[Unit], maxConcurrentThreadBatches: Int)
  val ALLOWED_ATTEMPTS = 2
  val MIN_TIME_BETWEEN_NOTIFICATIONS = 15 minutes
  val RECENT_ACTIVITY_WINDOW = 24 hours
  val MAX_CONCURRENT_MESSAGE_THREAD_BATCHES = 1
}

abstract class ElizaEmailNotifierActor[T <: ParticipantThread](airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import ElizaEmailNotifierActor._

  private val participantThreadBatchQueue = new mutable.Queue[ParticipantThreadBatch[T]]()
  private var threadBatchesBeingProcessed = 0
  private val failures = mutable.Map[Id[MessageThread], Int]().withDefaultValue(0)

  protected def getParticipantThreadsToProcess(): Seq[T]
  protected def emailUnreadMessagesForParticipantThreadBatch(batch: ParticipantThreadBatch[T]): Future[Unit]

  def receive = {

    case SendNextEmails(maxConcurrentThreadBatches) =>
      log.info("Attempting to process next user emails")
      if (threadBatchesBeingProcessed == 0) {
        if (participantThreadBatchQueue.isEmpty) {
          val participantThreadBatches = getParticipantThreadsToProcess().groupBy(_.threadId).values.map(ParticipantThreadBatch(_)).toSeq
          participantThreadBatches.foreach(participantThreadBatchQueue.enqueue(_))
        }

        while (threadBatchesBeingProcessed < maxConcurrentThreadBatches && participantThreadBatchQueue.nonEmpty) {
          log.info(s"userThreadBatchQueue size: ${participantThreadBatchQueue.size}")
          val batch = participantThreadBatchQueue.dequeue()
          val threadId = batch.threadId

          if (failures(threadId) < ALLOWED_ATTEMPTS) {
            emailUnreadMessagesForParticipantThreadBatch(batch).onComplete { result => self ! DoneWithThread(threadId, result, maxConcurrentThreadBatches) }
            threadBatchesBeingProcessed += 1
          }
        }
      }

    case DoneWithThread(threadId, result, maxConcurrentThreadBatches) =>
      threadBatchesBeingProcessed -= 1
      result match {
        case Success(_) => failures -= threadId
        case Failure(ex) =>
          failures(threadId) += 1
          airbrake.notify(s"Failure occurred during user thread batch processing: threadId = ${threadId}}", ex)
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
    scheduleTaskOnOneMachine(nonUserEmailActor.system, 30 seconds, 2 minutes, nonUserEmailActor.ref, SendNextEmails(), "nonUserEmail")
    scheduleTaskOnOneMachine(userEmailActor.system, 90 seconds, 2 minutes, userEmailActor.ref, SendNextEmails(), "userEmail")
  }

  def sendEmails() {
    nonUserEmailActor.ref ! SendNextEmails()
    userEmailActor.ref ! SendNextEmails()
  }
}
