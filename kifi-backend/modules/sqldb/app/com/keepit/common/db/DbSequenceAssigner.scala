package com.keepit.common.db

import com.keepit.common.db.slick.{ Database, Repo, SeqNumberFunction }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequenceNumberAssignmentStalling, SequenceAssigner }
import java.util.concurrent.atomic.AtomicInteger
import scala.math._

abstract class DbSequenceAssigner[M <: ModelWithSeqNumber[M]](
    db: Database,
    repo: Repo[M] with SeqNumberFunction[M],
    airbrake: AirbrakeNotifier) extends SequenceAssigner with Logging {

  val batchSize: Int = 20 // override this if necessary
  private[this] val errorCount = new AtomicInteger(0)
  protected val MAX_ITER = 100

  override def assignSequenceNumbers(): Unit = {
    var done = false
    var iter = 0
    errorCount.set(0)
    var currentBatchSize = batchSize
    while (!done && iter < MAX_ITER) {
      iter += 1
      log.info(s"${this.getClass.getSimpleName} going to assignSeqNum, iter = $iter")
      try {
        done = (db.readWrite { implicit session => repo.assignSequenceNumbers(currentBatchSize) } <= 0)
        errorCount.set(0)
        currentBatchSize = batchSize
        log.info(s"${this.getClass.getSimpleName} succeeded to assignSeqNum, iter = $iter")
      } catch {
        case e: UnsupportedOperationException =>
          reportUnsupported(e)
          throw e
        case e: Throwable =>
          log.error(s"${this.getClass.getSimpleName} failed to assignSeqNum, iter = $iter")
          if (errorCount.getAndIncrement > 1) {
            throw new Exception(s"Error #${errorCount.get} with batch size $currentBatchSize on ${this.getClass.getSimpleName}", e)
          } else {
            currentBatchSize = max(currentBatchSize / 2, 1)
            log.warn(s"${this.getClass.getSimpleName} reduced batch size to $currentBatchSize")
            //we hate sleeping in production! but here we feel its something we must do to avoid repeating deadlocks
            Thread.sleep(errorCount.get * 100)
          }
      }
    }
  }

  private[this] var lastMinDeferredSeqNumOpt: Option[Long] = None

  override def sanityCheck(): Unit = {
    val minDeferredSeqNumOpt = try {
      db.readOnlyReplica { implicit session => repo.minDeferredSequenceNumber() }
    } catch {
      case e: UnsupportedOperationException =>
        reportUnsupported(e)
        throw e
    }

    for (lastMinDeferredSeqNum <- lastMinDeferredSeqNumOpt; minDeferredSeqNum <- minDeferredSeqNumOpt) {
      if (minDeferredSeqNum <= lastMinDeferredSeqNum) {
        val ex = new SequenceNumberAssignmentStalling(this.getClass.getSimpleName, minDeferredSeqNum)
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
