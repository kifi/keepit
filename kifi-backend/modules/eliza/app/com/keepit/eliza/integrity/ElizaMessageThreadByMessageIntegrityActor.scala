package com.keepit.eliza.integrity

import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
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

private[this] object ElizaMessageThreadByMessageIntegrityConfig {
  val watermark = Name[SequenceNumber[ElizaMessage]]("integrity_message_thread_by_message")
  val fetchSize: Int = 25
}

class ElizaMessageThreadByMessageIntegrityActor @Inject() (
  db: Database,
  clock: Clock,
  systemValueRepo: SystemValueRepo,
  shoebox: ShoeboxServiceClient,
  discussionCommander: ElizaDiscussionCommander,
  threadRepo: MessageThreadRepo,
  messageRepo: MessageRepo,
  userThreadRepo: UserThreadRepo,
  nuThreadRepo: NonUserThreadRepo,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends FortyTwoActor(airbrake) with BatchProcessingActor[ElizaMessage] {
  import ElizaMessageThreadByMessageIntegrityConfig._

  protected def nextBatch: Future[Seq[ElizaMessage]] = SafeFuture {
    db.readOnlyMaster { implicit session =>
      val seq = systemValueRepo.getSequenceNumber(watermark) getOrElse SequenceNumber.ZERO
      messageRepo.getBySequenceNumber(seq, fetchSize)
    }
  }

  protected def processBatch(msgs: Seq[ElizaMessage]): Future[Unit] = SafeFuture {
    if (msgs.nonEmpty) {
      val keepIds = msgs.map(_.keepId).toSet
      db.readWrite { implicit s =>
        val numMsgsByKeep = messageRepo.countByKeeps(keepIds)
        val threadsByKeep = threadRepo.getByKeepIds(keepIds)
        threadsByKeep.foreach {
          case (kId, thread) => numMsgsByKeep.get(kId).foreach { numMsgs =>
            if (thread.numMessages != numMsgs) threadRepo.save(thread.withNumMessages(numMsgs))
          }
        }
      }

      db.readWrite { implicit s =>
        systemValueRepo.setSequenceNumber(watermark, msgs.map(_.seq).max)
      }
    }
  }
}
