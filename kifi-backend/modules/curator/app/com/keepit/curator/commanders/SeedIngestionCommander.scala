package com.keepit.curator.commanders

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
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
    allLibraryIngestor: AllLibrarySeedIngestionHelper,
    topUrisIngestor: TopUriSeedIngestionHelper,
    libraryMembershipIngestor: LibraryMembershipIngestionHelper,
    airbrake: AirbrakeNotifier,
    rawSeedsRepo: RawSeedItemRepo,
    keepInfoRepo: CuratorKeepInfoRepo,
    libMembershipRepo: CuratorLibraryMembershipInfoRepo,
    experimentCommander: RemoteUserExperimentCommander,
    rawSeedSeqNumAssigner: RawSeedItemSequenceNumberAssigner,
    db: Database) extends Logging {

  val INGESTION_BATCH_SIZE = 50

  val MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER = 20

  val MIN_KEEPS_FOR_RECOS = 20
  val MIN_LIBS_FOR_RECOS = 1

  def usersToIngestGraphDataFor(): Seq[Id[User]] = getUsersWithSufficientData().toSeq

  val keepIngestionLock = new ReactiveLock()
  val libraryIngestionLock = new ReactiveLock()

  def ingestAll(): Unit = {
    log.info("XYZ: starting ingestAll outside lock")

    keepIngestionLock.withLockFuture {
      log.info("XYZ: starting ingestAll inside keepIngestLock")

      val ingestKeepsF = ingestAllKeeps().flatMap { _ =>
        log.info("XYZ: ingest all keeps future completed")
        rawSeedSeqNumAssigner.assignSequenceNumbers()
        val userIds = usersToIngestGraphDataFor()
        FutureHelpers.sequentialExec(userIds)(ingestTopUris).map { res =>
          rawSeedSeqNumAssigner.assignSequenceNumbers()
          res
        }

      }

      ingestKeepsF.onComplete {
        case Failure(ex) =>
          log.error("Failure occurred during keeps ingestion.")
          airbrake.notify("Failure occurred during keeps ingestion.", ex)
        case _ =>
      }

      ingestKeepsF map (_ => true)
    }

    libraryIngestionLock.withLockFuture {
      log.info("XYZ: starting ingestAll inside libraryIngestLock")

      val ingestLibMembershipsF = ingestLibraryMemberships()
      ingestLibMembershipsF.onComplete {
        case Failure(ex) =>
          log.error("Failure occurred during library membership ingestion.")
          airbrake.notify("Failure occurred during library membership ingestion.", ex)
        case _ => log.info("XYZ: ingest library membership future completed")
      }

      val ingestLibrariesF = ingestAllLibraries()
      ingestLibrariesF.onComplete {
        case Failure(ex) =>
          log.error("Failure occurred during library ingestion.")
          airbrake.notify("Failure occurred during library ingestion.", ex)
        case _ => log.info("XYZ: ingest library future completed")
      }

      Future.sequence(ingestLibMembershipsF :: ingestLibrariesF :: Nil) map (_ => ())
    }
  }

  def ingestAllKeeps(): Future[Unit] = FutureHelpers.whilef(allKeepIngestor(INGESTION_BATCH_SIZE)) {
    log.info("XYZ: Ingested one batch of keeps.")
    rawSeedSeqNumAssigner.assignSequenceNumbers()
    log.info("XYZ: Assigned Sequence Numbers")
  }

  def ingestAllLibraries(): Future[Unit] = FutureHelpers.whilef(allLibraryIngestor(INGESTION_BATCH_SIZE)) {
    log.info("XYZ: Ingested one batch of libraries.")
  }

  def ingestLibraryMemberships(): Future[Unit] = FutureHelpers.whilef(libraryMembershipIngestor(INGESTION_BATCH_SIZE)) {
    log.info("XYZ: Ingested one batch of library memberships.")
  }

  def ingestTopUris(userId: Id[User]): Future[Unit] = {
    log.info("XYZ: Triggered Top Uri ingestion for " + userId)
    topUrisIngestor(userId).map { _ =>
      log.info("XYZ: Completed Top Uri ingestion for " + userId)
      rawSeedSeqNumAssigner.assignSequenceNumbers()
      ()
    }
  }

  private def cookSeedItem(userId: Id[User], rawItem: RawSeedItem, keepers: Keepers): SeedItem = SeedItem(
    userId = userId,
    uriId = rawItem.uriId,
    url = rawItem.url,
    seq = SequenceNumber[SeedItem](rawItem.seq.value),
    priorScore = rawItem.priorScore,
    timesKept = rawItem.timesKept,
    lastSeen = rawItem.lastSeen,
    lastKept = rawItem.lastKept,
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
    val timer = new NamedStatsdTimer("SeedIngestionCommander.getDiscoverableBySeqNumAndUser")
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getDiscoverableBySeqNumAndUser(SequenceNumber[RawSeedItem](start.value), userId, maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        val res = cookSeedItem(userId, rawItem, keepers)
        timer.stopAndReport()
        res
      }
    }
  }

  def getPopularDiscoverableBySeqNumAndUser(start: SequenceNumber[SeedItem], userId: Id[User], maxBatchSize: Int): Future[Seq[SeedItem]] = {
    val timer = new NamedStatsdTimer("SeedIngestionCommander.getPopularDiscoverableBySeqNumAndUser")
    db.readOnlyReplicaAsync { implicit session =>
      rawSeedsRepo.getPopularDiscoverableBySeqNumAndUser(SequenceNumber[RawSeedItem](start.value), userId, maxBatchSize).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        val res = cookSeedItem(userId, rawItem, keepers)
        timer.stopAndReport()
        res
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

  def getPublicSeedItem(uriId: Id[NormalizedURI]): Option[PublicSeedItem] = {
    db.readOnlyReplica { implicit session =>
      rawSeedsRepo.getFirstByUriId(uriId).map { rawItem =>
        val keepers = if (rawItem.timesKept > MAX_INDIVIDUAL_KEEPERS_TO_CONSIDER) {
          Keepers.TooMany
        } else {
          Keepers.ReasonableNumber(keepInfoRepo.getKeepersByUriId(rawItem.uriId))
        }
        cookPublicSeedItem(rawItem, keepers)
      }
    }
  }

  def getUsersWithSufficientData(minUserKeepCount: Int = MIN_KEEPS_FOR_RECOS): Set[Id[User]] = {
    db.readOnlyReplica { implicit session =>
      keepInfoRepo.getUsersWithKeepsCounts()
    }.filter {
      case (userId, keepCount) => keepCount >= minUserKeepCount
    }.map(_._1).toSet ++ db.readOnlyReplica { implicit session => libMembershipRepo.getUsersFollowingALibrary() }
  }

  def forceIngestGraphData(userId: Id[User]): Future[Unit] = {
    topUrisIngestor(userId, force = true).map(_ => ())
  }

}
