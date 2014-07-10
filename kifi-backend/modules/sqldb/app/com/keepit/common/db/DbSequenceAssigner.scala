package com.keepit.common.db

import com.keepit.common.db.slick.{ Database, Repo, SeqNumberFunction }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequenceNumberAssignmentStalling, SequenceAssigner }

abstract class DbSequenceAssigner[M <: ModelWithSeqNumber[M]](
    db: Database,
    repo: Repo[M] with SeqNumberFunction[M],
    airbrake: AirbrakeNotifier) extends SequenceAssigner with Logging {

  val batchSize: Int = 20 // override this if necessary

  override def assignSequenceNumbers(): Unit = {
    try {
      while (db.readWrite { implicit session => repo.assignSequenceNumbers(batchSize) } > 0) {}
    } catch {
      case e: UnsupportedOperationException =>
        reportUnsupported(e)
        throw e
    }
  }

  private[this] var lastMinDeferredSeqNumOpt: Option[Long] = None

  override def sanityCheck(): Unit = {
    val minDeferredSeqNumOpt = try {
      db.readOnlyMaster { implicit session => repo.minDeferredSequenceNumber() }
    } catch {
      case e: UnsupportedOperationException =>
        reportUnsupported(e)
        throw e
    }

    for (lastMinDeferredSeqNum <- lastMinDeferredSeqNumOpt; minDeferredSeqNum <- minDeferredSeqNumOpt) {
      if (minDeferredSeqNum <= lastMinDeferredSeqNum) {
        val ex = new SequenceNumberAssignmentStalling(minDeferredSeqNum)
        log.warn(ex.getMessage)
        airbrake.notify(ex)
      }
    }

    lastMinDeferredSeqNumOpt = minDeferredSeqNumOpt
  }

  override def onError(e: Throwable): Unit = { airbrake.notify(s"Error in ${this.getClass().getSimpleName()}", e) }

  private[this] var reportIfUnsupported = true

  private[this] def reportUnsupported(e: UnsupportedOperationException): Unit = {
    if (reportIfUnsupported) {
      reportIfUnsupported = false // report only once
      airbrake.notify(s"FATAL: deferred sequence assignment is not supported by ${repo.getClass.getSimpleName()}", e)
    }
  }
}
