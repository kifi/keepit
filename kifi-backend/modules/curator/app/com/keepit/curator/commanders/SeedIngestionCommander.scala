package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

@Singleton
class SeedIngestionCommander @Inject() (
    allKeepIngestor: AllKeepSeedIngestionHelper,
    airbrake: AirbrakeNotifier) extends Logging {

  val INGESTION_BATCH_SIZE = 50

  @volatile var ingestionInProgress: Boolean = false

  def ingestAll(): Unit = if (!ingestionInProgress) synchronized {
    if (!ingestionInProgress) {
      ingestionInProgress = true
      FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
        log.info("Ingested one batch of keeps.")
      }.onComplete {
        case Success(_) => ingestionInProgress = false
        case Failure(ex) => {
          log.error("Failure occured during all keeps ingestion.")
          airbrake.notify("Failure occured during all keeps ingestion.", ex)
          ingestionInProgress = false
        }
      }
    }
  }
}
