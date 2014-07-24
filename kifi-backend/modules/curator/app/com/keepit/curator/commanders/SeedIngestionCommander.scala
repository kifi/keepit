package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.curator.model.{ SeedItem, RawSeedItem, Keepers, RawSeedItemRepo, CuratorKeepInfoRepo }
import com.keepit.model.User
import com.keepit.common.db.slick.Database

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
    airbrake: AirbrakeNotifier,
    rawSeedsRepo: RawSeedItemRepo,
    keepInfoRepo: CuratorKeepInfoRepo,
    db: Database) extends Logging {

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

  private def cook(userId: Id[User], rawItem: RawSeedItem, keepers: Keepers): SeedItem = SeedItem(
    userId = userId,
    uriId = rawItem.uriId,
    seq = SequenceNumber[SeedItem](rawItem.seq.value),
    timesKept = rawItem.timesKept,
    lastSeen = rawItem.lastSeen,
    keepers = keepers
  )

  def getBySeqNumAndUser(start: SequenceNumber[SeedItem], userId: Id[User], maxBatchSize: Int): Future[Seq[SeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getBySeqNumAndUser(SequenceNumber[RawSeedItem](start.value), userId, maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > 100) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cook(userId, rawItem, keepers)
      }
    }
  }

  //this is a methods for (manual, not unit) testing of data flow and scoring, not meant for user facing content or scale
  def getRecentItems(userId: Id[User], howManyMax: Int): Future[Seq[SeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getRecent(userId, howManyMax).map { rawItem =>
        val keepers = if (rawItem.timesKept > 100) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cook(userId, rawItem, keepers)
      }
    }
  }

}
