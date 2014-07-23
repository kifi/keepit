package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.inject.{ Inject, Singleton }
import com.keepit.model.User

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }
import scala.concurrent.{ Future, Promise }

import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class SeedIngestionCommander @Inject() (
    allKeepIngestor: AllKeepSeedIngestionHelper,
    topUrisIngestor: TopUriSeedIngestionHelper,
    airbrake: AirbrakeNotifier) extends Logging {

  val INGESTION_BATCH_SIZE = 50

  val ingestionInProgress: AtomicBoolean = new AtomicBoolean(false)

  def ingestAll(): Future[Boolean] = if (ingestionInProgress.compareAndSet(false, true)) {
    val fut = FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
      log.info("Ingested one batch of keeps.")
    }
    fut.onComplete {
      case Success(_) => ingestionInProgress.set(false)
      case Failure(ex) => {
        log.error("Failure occured during all keeps ingestion.")
        airbrake.notify("Failure occured during all keeps ingestion.", ex)
        ingestionInProgress.set(false)
      }
    }
    fut.map(_ => true)
  } else {
    Future.successful(false)
  }

  def ingestTopUris(userId: Id[User]): Future[Boolean] = topUrisIngestor(userId, INGESTION_BATCH_SIZE)
}
