package com.keepit.eliza.shoebox

import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.eliza.model._
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.kifi.juggle.BatchProcessingActor

import scala.concurrent.{ ExecutionContext, Future }

object ElizaKeepIngestingActor {
  val elizaKeepSeq = Name[SequenceNumber[Keep]]("eliza_keep")
  val fetchSize: Int = 25
}

class ElizaKeepIngestingActor @Inject() (
  db: Database,
  clock: Clock,
  systemValueRepo: SystemValueRepo,
  shoebox: ShoeboxServiceClient,
  discussionCommander: ElizaDiscussionCommander,
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  nuThreadRepo: NonUserThreadRepo,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends FortyTwoActor(airbrake) with BatchProcessingActor[CrossServiceKeep] {
  import ElizaKeepIngestingActor._

  protected def nextBatch: Future[Seq[CrossServiceKeep]] = SafeFuture.wrap {
    log.info(s"Ingesting keeps from Shoebox...")
    val seqNum = db.readOnlyMaster { implicit session =>
      systemValueRepo.getSequenceNumber(elizaKeepSeq) getOrElse SequenceNumber.ZERO
    }
    shoebox.getCrossServiceKeepsAndTagsChanged(seqNum, fetchSize).map(_.map(_.keep))
  }

  protected def processBatch(keeps: Seq[CrossServiceKeep]): Future[Unit] = SafeFuture {
    if (keeps.nonEmpty) db.readWrite { implicit session =>
      val (liveKeeps, deadKeeps) = keeps.partition(_.isActive)
      discussionCommander.deleteThreadsForKeeps(deadKeeps.map(_.id).toSet)

      val (keepsWithoutThreads, threadsThatNeedFixing) = {
        val threadsByKeep = threadRepo.getByKeepIds(liveKeeps.map(_.id).toSet)
        liveKeeps.map { keep =>
          threadsByKeep.get(keep.id).fold[Either[CrossServiceKeep, (CrossServiceKeep, MessageThread)]](Left(keep))(thread => Right(keep -> thread))
        }.partitionEithers
      }
      threadsThatNeedFixing.foreach {
        case (keep, thread) =>
          val newUsers = keep.users -- thread.participants.allUsers
          val newEmails = keep.emails -- thread.participants.allEmails
          val deadUsers = thread.participants.allUsers -- keep.users
          val deadEmails = thread.participants.allEmails -- keep.emails
          val newThread = threadRepo.save {
            thread
              .withUriId(keep.uriId)
              .withParticipants(
                thread.participants
                  .plusUsers(newUsers)
                  .minusUsers(deadUsers)
                  .plusEmails(newEmails)
                  .minusEmails(deadEmails)
              )
          }
          newUsers.foreach { u => userThreadRepo.intern(UserThread.forMessageThread(newThread)(u)) }
          newEmails.foreach { e => nuThreadRepo.intern(NonUserThread.forMessageThread(newThread)(e)) }

          deadUsers.foreach { u => userThreadRepo.getUserThread(u, keep.id).foreach(x => userThreadRepo.deactivate(x)) }
          deadEmails.foreach { e => nuThreadRepo.getByKeepAndEmail(keep.id, e).foreach(x => nuThreadRepo.deactivate(x)) }
      }
      systemValueRepo.setSequenceNumber(elizaKeepSeq, keeps.map(_.seq).max)
      log.info(s"Ingested ${keeps.length} keeps from Shoebox, fixed uri / recipients for ${threadsThatNeedFixing.length} of them")
    }
  }
}
