package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model.{ KeepRepo, ChangedURIRepo }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.service.ServiceType
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging

trait SequenceNumberChecker {
  val service: ServiceType
  def check(): Unit
}

class SequenceNumberOffException(message: String) extends Exception(message)

class ElizaSequenceNumberChecker @Inject() (
    db: Database,
    keepRepo: KeepRepo,
    elizaServiceClient: ElizaServiceClient,
    airbrake: AirbrakeNotifier) extends SequenceNumberChecker with Logging {

  private val threshold = 500
  val service = ServiceType.ELIZA

  def check(): Unit = {
    checkRenormalizationSequenceNumber()
  }

  protected def checkRenormalizationSequenceNumber(): Unit = {
    db.readOnlyMaster { implicit session => keepRepo.maxSequenceNumber() }.foreach { shoeboxKeepSeq =>
      elizaServiceClient.getKeepIngestionSequenceNumber().map { elizaKeepSeq =>
        log.info(s"[ElizaSequenceNumberChecker] Keep Sequence Number: Shoebox $shoeboxKeepSeq vs Eliza: $elizaKeepSeq")
        if (shoeboxKeepSeq - elizaKeepSeq > threshold) {
          airbrake.notify(AirbrakeError(new SequenceNumberOffException(s"[ElizaSequenceNumberChecker] Eliza Keep Ingestion is falling behind: Shoebox $shoeboxKeepSeq vs Eliza: $elizaKeepSeq")))
        }
      }
    }
  }
}

