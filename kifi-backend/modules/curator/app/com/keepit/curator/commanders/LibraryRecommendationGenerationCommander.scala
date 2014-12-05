package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.model.{ CuratorLibraryInfo, CuratorLibraryInfoRepo, LibraryRecommendation, LibraryRecommendationGenerationState, LibraryRecommendationGenerationStateRepo, LibraryRecommendationRepo, LibraryRecommendationStates }
import com.keepit.curator.{ LibraryScoringHelper, ScoredLibraryInfo }
import com.keepit.model.{ ExperimentType, Library, LibraryVisibility, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class LibraryRecommendationGenerationCommander @Inject() (
    scoringHelper: LibraryScoringHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    libraryInfoRepo: CuratorLibraryInfoRepo,
    libraryRecRepo: LibraryRecommendationRepo,
    genStateRepo: LibraryRecommendationGenerationStateRepo,
    experimentCommander: RemoteUserExperimentCommander,
    serviceDiscovery: ServiceDiscovery) extends Logging {

  val recommendationGenerationLock = new ReactiveLock(8)

  private def usersToPrecomputeRecommendationsFor(): Future[Seq[Id[User]]] =
    experimentCommander.getUsersByExperiment(ExperimentType.CURATOR_LIBRARY_RECOS).
      map(users => users.map(_.id.get).toSeq)

  def getTopRecommendations(userId: Id[User], howManyMax: Int): Future[Seq[LibraryRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      libraryRecRepo.getByTopMasterScore(userId, howManyMax)
    }
  }

  private def shouldInclude(scoredLibrary: ScoredLibraryInfo): Boolean = {
    true // TODO(josh)
  }

  private def getStateOfUser(userId: Id[User]): LibraryRecommendationGenerationState = {
    db.readOnlyMaster { implicit session =>
      genStateRepo.getByUserId(userId)
    } getOrElse {
      LibraryRecommendationGenerationState(userId = userId)
    }
  }

  private def initialLibraryRecoFilterForUser(userId: Id[User])(libraryInfo: CuratorLibraryInfo): Boolean = {
    libraryInfo.ownerId != userId && libraryInfo.keepCount > 0 && libraryInfo.visibility != LibraryVisibility.SECRET
    // TODO(josh) more checks (min followers? max age?)
  }

  private def getCandidateLibrariesForUser(userId: Id[User], state: LibraryRecommendationGenerationState): (Seq[CuratorLibraryInfo], SequenceNumber[CuratorLibraryInfo]) = {
    val libs = db.readOnlyReplica { implicit session => libraryInfoRepo.getBySeqNum(state.seq, 200) }
    val filteredLibs = libs filter initialLibraryRecoFilterForUser(userId)
    val maxLibSeqNum = libs.lastOption map (_.seq) getOrElse state.seq

    // returns the last seq number we looked at whether we're using it or not, to ensure we don't keep fetching it
    (filteredLibs, maxLibSeqNum)
  }

  private def saveLibraryRecommendations(scoredLibraryInfos: Seq[ScoredLibraryInfo], userId: Id[User]): Unit =
    db.readWrite { implicit s =>
      scoredLibraryInfos foreach { scoredLibraryInfo =>
        val recoOpt = libraryRecRepo.getByLibraryAndUserId(scoredLibraryInfo.libraryId, userId, None)
        recoOpt.map { reco =>
          libraryRecRepo.save(reco.copy(
            state = LibraryRecommendationStates.ACTIVE,
            updatedAt = currentDateTime,
            masterScore = scoredLibraryInfo.masterScore,
            allScores = scoredLibraryInfo.allScores
          ))
        } getOrElse {
          libraryRecRepo.save(LibraryRecommendation(
            libraryId = scoredLibraryInfo.libraryId,
            userId = userId,
            masterScore = scoredLibraryInfo.masterScore,
            allScores = scoredLibraryInfo.allScores))
        }
      }
    }

  private def processLibraries(candidates: Seq[CuratorLibraryInfo], newState: LibraryRecommendationGenerationState,
    userId: Id[User], alwaysInclude: Set[Id[Library]]): Future[Unit] = {
    scoringHelper(userId, candidates) flatMap { scores =>
      val toBeSaved = scores filter (si => alwaysInclude.contains(si.libraryId) || shouldInclude(si))
      saveLibraryRecommendations(toBeSaved, userId)
      precomputeRecommendationsForUser(userId, alwaysInclude, newState)
    }
  }

  private def precomputeRecommendationsForUser(userId: Id[User], alwaysInclude: Set[Id[Library]], recoGenState: LibraryRecommendationGenerationState): Future[Unit] = {
    if (serviceDiscovery.isLeader()) {
      log.info(s"precomputeRecommendationsForUser called userId=$userId seq=${recoGenState.seq}")
      val (candidateLibraries, newSeqNum) = getCandidateLibrariesForUser(userId, recoGenState)

      val newState = recoGenState.copy(seq = newSeqNum)
      if (candidateLibraries.isEmpty) {
        val savedNewState = db.readWrite { implicit session => genStateRepo.save(newState) }
        if (recoGenState.seq < newSeqNum) precomputeRecommendationsForUser(userId, alwaysInclude, savedNewState)
        else Future.successful()
      } else processLibraries(candidateLibraries, newState, userId, alwaysInclude)
    } else {
      log.warn("precomputeRecommendationsForUser doing nothing on non-leader")
      Future.successful()
    }
  }

  def precomputeRecommendations(): Future[Unit] = {
    usersToPrecomputeRecommendationsFor() flatMap { userIds =>
      Future.sequence {
        if (recommendationGenerationLock.waiting < userIds.length + 1) {
          userIds map { userId =>
            statsd.time("curator.libRecosGenForUser", 1) { _ =>
              val alwaysInclude: Set[Id[Library]] = db.readOnlyReplica { implicit session => libraryRecRepo.getLibraryIdsForUser(userId) }
              recommendationGenerationLock.withLockFuture {
                val state: LibraryRecommendationGenerationState = db.readOnlyReplica { implicit session => getStateOfUser(userId) }
                new SafeFuture(precomputeRecommendationsForUser(userId, alwaysInclude, state))
              }
            }
          }
        } else {
          log.info(s"precomputeRecommendations skipping: lock.waiting=${recommendationGenerationLock.waiting} userIds.length=${userIds.length}}")
          Seq.empty
        }
      }
    } map (_ => ())
  }

}
