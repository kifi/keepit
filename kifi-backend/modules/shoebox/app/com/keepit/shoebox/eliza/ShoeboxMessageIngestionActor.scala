package com.keepit.shoebox.eliza

import com.google.inject.Inject
import com.keepit.commanders.KeepCommander
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient, LibraryToSlackChannelPusher }
import com.kifi.juggle.BatchProcessingActor

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

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
  slackPusher: LibraryToSlackChannelPusher,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with BatchProcessingActor[CrossServiceMessage] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)
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
    case Failure(error) =>
      log.error("Could not fetch new messages from Eliza", error)
      slackLog.error("Could not fetch new messages from Eliza:", error.getMessage)
  }

  protected def processBatch(messages: Seq[CrossServiceMessage]): Future[Unit] = SafeFuture {
    messages.map(_.seq).maxOpt.foreach { maxSeq =>
      val messagesByKeep = messages.groupBy(_.keep)
      db.readWrite { implicit session =>
        val keeps = keepRepo.getByIds(messagesByKeep.keySet).values
        session.onTransactionSuccess {
          slackPusher.schedule(keeps.flatMap(_.connections.libraries).toSet)
        }
        keeps.foreach { keep =>
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
