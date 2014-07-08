package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.model.ChangedURIRepo
import com.keepit.eliza.ElizaServiceClient
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
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
  changedUriRepo: ChangedURIRepo,
  elizaServiceClient: ElizaServiceClient,
  airbrake: AirbrakeNotifier
) extends SequenceNumberChecker with Logging {

  private val threshold = 500
  val service = ServiceType.ELIZA

  def check(): Unit = {
    checkRenormalizationSequenceNumber()
  }

  protected def checkRenormalizationSequenceNumber(): Unit = {
    val elizaRenormalizationSequenceNumber = elizaServiceClient.getRenormalizationSequenceNumber()
    val shoeboxRenormalizationSequenceNumber = db.readOnlyMaster { implicit session => changedUriRepo.getHighestSeqNum().get }
    elizaRenormalizationSequenceNumber.foreach { elizaSeq =>
      log.info(s"[Renormalization] Sequence Numbers of Shoebox: $shoeboxRenormalizationSequenceNumber vs Eliza: $elizaSeq")
      if (shoeboxRenormalizationSequenceNumber - elizaSeq > threshold) {
        airbrake.notify(AirbrakeError(new SequenceNumberOffException(s"[Renormalization] Eliza is falling behind, at sequence number $elizaSeq while Shoebox is at $shoeboxRenormalizationSequenceNumber")))
      }
    }
  }
}




