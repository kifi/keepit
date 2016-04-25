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

private[this] object ElizaMessageByMessageIntegrityConfig {
  val watermark = Name[SequenceNumber[ElizaMessage]]("integrity_message_by_message")
  val fetchSize: Int = 25
}

class ElizaMessageByMessageIntegrityActor @Inject() (
  db: Database,
  clock: Clock,
  systemValueRepo: SystemValueRepo,
  messageRepo: MessageRepo,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends FortyTwoActor(airbrake) with BatchProcessingActor[ElizaMessage] {
  import ElizaMessageByMessageIntegrityConfig._

  protected def nextBatch: Future[Seq[ElizaMessage]] = SafeFuture {
    db.readOnlyMaster { implicit session =>
      val seq = systemValueRepo.getSequenceNumber(watermark) getOrElse SequenceNumber.ZERO
      messageRepo.getBySequenceNumber(seq, fetchSize)
    }
  }

  protected def processBatch(msgs: Seq[ElizaMessage]): Future[Unit] = SafeFuture {
    if (msgs.nonEmpty) {
      db.readWrite { implicit s =>
        msgs.foreach { msg =>
          val commentIdx = messageRepo.getCommentIndex(msg)
          if (msg.commentIndexOnKeep != commentIdx) messageRepo.save(msg.withCommentIndex(commentIdx))
        }
      }

      db.readWrite { implicit s =>
        systemValueRepo.setSequenceNumber(watermark, msgs.map(_.seq).max)
      }
    }
  }
}
