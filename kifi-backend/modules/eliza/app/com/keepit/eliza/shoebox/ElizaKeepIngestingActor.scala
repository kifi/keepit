package com.keepit.eliza.shoebox

import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.core._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.kifi.juggle.BatchProcessingActor

import scala.concurrent.{ ExecutionContext, Future }

object ElizaKeepIngestingActor {
  val elizaKeepSeq = Name[SequenceNumber[Keep]]("eliza_keep")
  val fetchSize: Int = 100
}

class ElizaKeepIngestingActor @Inject() (
  db: Database,
  systemValueRepo: SystemValueRepo,
  shoebox: ShoeboxServiceClient,
  discussionCommander: ElizaDiscussionCommander,
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
    keeps.map(_.seq).maxOpt.foreach { maxSeq =>
      val deadKeeps = keeps.filter(!_.isActive)
      discussionCommander.deleteThreadsForKeeps(deadKeeps.map(_.id).toSet)
      db.readWrite { implicit s =>
        systemValueRepo.setSequenceNumber(elizaKeepSeq, maxSeq)
      }
    }
    log.info(s"Ingested ${keeps.length} keeps from Shoebox")
  }
}
