package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.concurrent.ReactiveLock
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

  val GRAPH_INGESTION_WHITELIST: Seq[Id[User]] = Seq(
    1, //Eishay
    3, //Andrew
    7, //Yasu
    9, //Danny
    48, //Jared
    61, //Jen
    100, //Tamila
    115, //Yingjie
    134, //Léo
    243, //Stephen
    460, //Ray
    1114, //Martin
    2538, //Mark
    3466, //JP
    6498, //Tan
    6622, //David
    7100, //Aaron
    7456, //Josh
    7589, //Lydia
    8465, //Yiping
    8476 //Tommy
  ).map(Id[User](_)) //will go away once we release, just saving some computation/time for now

  val MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER = 100

  val ingestionLock = new ReactiveLock()

  def ingestAll(): Future[Boolean] = ingestionLock.withLockFuture {
    val fut = ingestAllKeeps().flatMap { _ =>
      FutureHelpers.sequentialExec(GRAPH_INGESTION_WHITELIST)(ingestTopUris)
    }
    fut.onComplete {
      case Failure(ex) => {
        log.error("Failure occured during ingestion.")
        airbrake.notify("Failure occured during ingestion.", ex)
      }
      case _ =>
    }
    fut.map(_ => true)
  }

  def ingestAllKeeps(): Future[Unit] = FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
    log.info("Ingested one batch of keeps.")
  }

  def ingestTopUris(userId: Id[User]): Future[Unit] = topUrisIngestor(userId, INGESTION_BATCH_SIZE).map(_ => ())

  private def cook(userId: Id[User], rawItem: RawSeedItem, keepers: Keepers): SeedItem = SeedItem(
    userId = userId,
    uriId = rawItem.uriId,
    url = rawItem.url,
    seq = SequenceNumber[SeedItem](rawItem.seq.value),
    priorScore = rawItem.priorScore,
    timesKept = rawItem.timesKept,
    lastSeen = rawItem.lastSeen,
    keepers = keepers,
    discoverable = rawItem.discoverable
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
      val items = (rawSeedsRepo.getByTopPriorScore(userId, howManyMax / 2) ++ rawSeedsRepo.getRecentGeneric(howManyMax / 2)).toSet.toSeq
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
