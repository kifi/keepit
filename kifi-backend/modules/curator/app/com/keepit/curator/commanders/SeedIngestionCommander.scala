package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }
import scala.concurrent.Future

@Singleton
class SeedIngestionCommander @Inject() (
    allKeepIngestor: AllKeepSeedIngestionHelper,
    airbrake: AirbrakeNotifier) extends Logging {

  val INGESTION_BATCH_SIZE = 50

  @volatile var ingestionFuture: Option[Future[Unit]] = None

  def ingestAll(): Future[Unit] = if (ingestionFuture.isEmpty) synchronized {
    if (ingestionFuture.isEmpty) {
      val fut = FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
        log.info("Ingested one batch of keeps.")
      }
      ingestionFuture = Some(fut)
      fut.onComplete {
        case Success(_) => ingestionFuture = None
        case Failure(ex) => {
          log.error("Failure occured during all keeps ingestion.")
          airbrake.notify("Failure occured during all keeps ingestion.", ex)
          ingestionFuture = None
        }
      }
      fut
    } else {
      ingestionFuture.get
    }
  }
  else {
    ingestionFuture.get
  }

}
