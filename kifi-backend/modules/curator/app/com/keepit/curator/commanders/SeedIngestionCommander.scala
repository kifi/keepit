package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.curator.model._
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

  val GRAPH_INGESTION_WHITELIST: Seq[Id[User]] = Seq(243, 6498, 134, 3, 1, 9, 2538, 61, 115, 100).map(Id[User](_)) //will go away once we release, just saving some computation for now

  val MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER = 100

  val ingestionInProgress: AtomicBoolean = new AtomicBoolean(false)

  def ingestAll(): Future[Boolean] = if (ingestionInProgress.compareAndSet(false, true)) {
    val fut = ingestAllKeeps().flatMap { _ =>
      FutureHelpers.sequentialExec(GRAPH_INGESTION_WHITELIST)(ingestTopUris)
    }
    fut.onComplete {
      case Success(_) => ingestionInProgress.set(false)
      case Failure(ex) => {
        log.error("Failure occured during ingestion.")
        airbrake.notify("Failure occured during ingestion.", ex)
        ingestionInProgress.set(false)
      }
    }
    fut.map(_ => true)
  } else {
    Future.successful(false)
  }

  def ingestAllKeeps(): Future[Unit] = FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
    log.info("Ingested one batch of keeps.")
  }

  def ingestTopUris(userId: Id[User]): Future[Unit] = topUrisIngestor(userId, INGESTION_BATCH_SIZE).map(_ => ())

  private def cook(userId: Id[User], rawItem: RawSeedItem, keepers: Keepers): SeedItem = SeedItem(
    userId = userId,
    uriId = rawItem.uriId,
    seq = SequenceNumber[SeedItem](rawItem.seq.value),
    priorScore = rawItem.priorScore,
    timesKept = rawItem.timesKept,
    lastSeen = rawItem.lastSeen,
    keepers = keepers
  )

  def getBySeqNumAndUser(start: SequenceNumber[SeedItem], userId: Id[User], maxBatchSize: Int): Future[Seq[SeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getBySeqNumAndUser(SequenceNumber[RawSeedItem](start.value), userId, maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
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
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cook(userId, rawItem, keepers)
      }
    }
  }

  //this is a methods for (manual, not unit) testing of data flow and scoring, not meant for user facing content or scale
  def getTopItems(userId: Id[User], howManyMax: Int): Future[Seq[SeedItem]] = {
    //gets higest scoring ruis for the user. If that's not enough get recent items as well
    db.readOnlyReplicaAsync { implicit session =>
      var items = rawSeedsRepo.getByTopPriorScore(userId, howManyMax)
      if (items.length < howManyMax) items = (items ++ rawSeedsRepo.getRecent(userId, howManyMax)).toSet.toSeq
      items.map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cook(userId, rawItem, keepers)
      }.filter { seedItem =>
        seedItem.keepers match {
          case Keepers.ReasonableNumber(users) => !users.contains(userId)
          case _ => false
        }
      }

    }
  }

}
