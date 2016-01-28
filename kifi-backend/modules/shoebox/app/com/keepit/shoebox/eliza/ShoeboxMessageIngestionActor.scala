package com.keepit.shoebox.eliza

import com.google.inject.{ Inject }
import com.keepit.commanders.KeepCommander
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.db.{ SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.discussion.{ Message, CrossServiceMessage }
import com.keepit.eliza.ElizaServiceClient
import com.kifi.juggle.BatchProcessingActor
import com.keepit.model._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Failure

object ShoeboxMessageIngestionActor {
  val shoeboxMessageSeq = Name[SequenceNumber[Message]]("shoebox_message")
  val fetchSize: Int = 100
}

class ShoeboxMessageIngestionActor @Inject() (
    db: Database,
    systemValueRepo: SystemValueRepo,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    eliza: ElizaServiceClient,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with BatchProcessingActor[CrossServiceMessage] {

  import ShoeboxMessageIngestionActor._

  protected def nextBatch: Future[Seq[CrossServiceMessage]] = {
    log.info(s"Ingesting new messages from Eliza...")
    SafeFuture {
      db.readOnlyMaster { implicit session =>
        systemValueRepo.getSequenceNumber(shoeboxMessageSeq) getOrElse SequenceNumber.ZERO
      }
    } flatMap { seqNum =>
      eliza.getMessagesChanged(seqNum, fetchSize)
    }
  } andThen {
    case Failure(error) => log.error("Could not fetch new messages from Eliza", error)
  }

  protected def processBatch(messages: Seq[CrossServiceMessage]): Future[Unit] = SafeFuture {
    messages.map(_.seq).maxOpt.foreach { maxSeq =>
      val messagesByKeep = messages.groupBy(_.keep)
      db.readWrite { implicit session =>
        keepRepo.getByIds(messagesByKeep.keySet).values.foreach { keep =>
          messagesByKeep.get(keep.id.get).foreach { msgs =>
            val updatedKeep = keep.withMessageSeq(msgs.map(_.seq).max)
            if (updatedKeep.messageSeq != keep.messageSeq) keepRepo.save(updatedKeep)

            val lastMessageTime = msgs.map(_.sentAt).maxBy(_.getMillis)
            keepCommander.updateLastActivityAtIfLater(keep.id.get, lastMessageTime)
          }
        }
        systemValueRepo.setSequenceNumber(shoeboxMessageSeq, maxSeq)
      }
      log.info(s"Ingested ${messages.length} messages from Eliza")
    }
  }
}
