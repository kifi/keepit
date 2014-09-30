package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.curator.model._
import com.keepit.model.{ NormalizedURI, ExperimentType, User }
import com.keepit.common.db.slick.Database
import com.keepit.commanders.RemoteUserExperimentCommander

import com.google.inject.{ Inject, Singleton }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.Failure
import scala.concurrent.Future

@Singleton
class SeedIngestionCommander @Inject() (
    allKeepIngestor: AllKeepSeedIngestionHelper,
    topUrisIngestor: TopUriSeedIngestionHelper,
    libraryMembershipIngestor: LibraryMembershipIngestionHelper,
    airbrake: AirbrakeNotifier,
    rawSeedsRepo: RawSeedItemRepo,
    keepInfoRepo: CuratorKeepInfoRepo,
    experimentCommander: RemoteUserExperimentCommander,
    db: Database) extends Logging {

  val INGESTION_BATCH_SIZE = 50

  val MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER = 100

  val MIN_KEEPS_FOR_RECOS = 400 //This will be gradually lowered to 10 or twenty throughout this week

  def usersToIngestGraphDataFor(): Future[Seq[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.RECOS_BETA).map(users => users.map(_.id.get).toSeq)

  val ingestionLock = new ReactiveLock()

  def ingestAll(): Future[Boolean] = ingestionLock.withLockFuture {
    val fut = ingestAllKeeps().flatMap { _ =>
      ingestLibraryMemberships.flatMap { _ =>
        usersToIngestGraphDataFor().flatMap { userIds =>
          FutureHelpers.sequentialExec(userIds)(ingestTopUris)
        }
      }
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

  def ingestLibraryMemberships(): Future[Unit] = FutureHelpers.whilef(libraryMembershipIngestor(INGESTION_BATCH_SIZE)) {
    log.info("Ingested one batch of libraries.")
  }

  def ingestTopUris(userId: Id[User]): Future[Unit] = topUrisIngestor(userId).map(_ => ())

  private def cookSeedItem(userId: Id[User], rawItem: RawSeedItem, keepers: Keepers): SeedItem = SeedItem(
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

  private def cookPublicSeedItem(rawItem: RawSeedItem, keepers: Keepers): PublicSeedItem = PublicSeedItem(
    uriId = rawItem.uriId,
    url = rawItem.url,
    seq = SequenceNumber[PublicSeedItem](rawItem.seq.value),
    timesKept = rawItem.timesKept,
    lastSeen = rawItem.lastSeen,
    keepers = keepers,
    discoverable = rawItem.discoverable
  )

  def getDiscoverableBySeqNumAndUser(start: SequenceNumber[SeedItem], userId: Id[User], maxBatchSize: Int): Future[Seq[SeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getDiscoverableBySeqNumAndUser(SequenceNumber[RawSeedItem](start.value), userId, maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cookSeedItem(userId, rawItem, keepers)
      }
    }
  }

  def getPreviousSeeds(userId: Id[User], uris: Seq[Id[NormalizedURI]]): Future[Seq[SeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      val rawSeeds = rawSeedsRepo.getByUserIdAndUriIds(userId, uris)
      rawSeeds.map { rawSeed =>
        val keepers =
          if (rawSeed.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
            Keepers.TooMany
          } else {
            Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawSeed.uriId))
          }
        cookSeedItem(userId, rawSeed, keepers)
      }
    }
  }

  def getBySeqNum(start: SequenceNumber[PublicSeedItem], maxBatchSize: Int): Future[Seq[PublicSeedItem]] = {
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getDiscoverableBySeqNum(SequenceNumber[RawSeedItem](start.value), maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cookPublicSeedItem(rawItem, keepers)
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
        cookSeedItem(userId, rawItem, keepers)
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
        cookSeedItem(userId, rawItem, keepers)
      }.filter { seedItem =>
        seedItem.keepers match {
          case Keepers.ReasonableNumber(users) => !users.contains(userId)
          case _ => false
        }
      }

    }
  }

  def getUsersWithSufficientData(): Set[Id[User]] = {
    db.readOnlyReplica { implicit session =>
      keepInfoRepo.getUsersWithKeepsCounts()
    }.filter {
      case (userId, keepCount) =>
        keepCount > MIN_KEEPS_FOR_RECOS
    }.map(_._1).toSet
  }

  def forceIngestGraphData(userId: Id[User]): Future[Unit] = {
    topUrisIngestor(userId, force = true).map(_ => ())
  }

}
