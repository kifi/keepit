package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.model.{ LibraryRecommendationStates, CuratorLibraryInfo, LibraryRecommendationGenerationState, LibraryRecommendation, LibraryRecommendationGenerationStateRepo, LibraryRecommendationRepo, CuratorLibraryInfoRepo }
import com.keepit.curator.{ LibraryScoringHelper, ScoredLibraryInfo }
import com.keepit.model.{ ExperimentType, Library, LibraryVisibility, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

case class LibraryRecoCandidate(userId: Id[User], libraryInfo: CuratorLibraryInfo) {
  val libraryId = libraryInfo.libraryId
}

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
    scoredLibrary.masterScore > 0 // TODO(josh)
  }

  private def getStateOfUser(userId: Id[User]): LibraryRecommendationGenerationState =
    db.readOnlyMaster { implicit session =>
      genStateRepo.getByUserId(userId)
    } getOrElse {
      LibraryRecommendationGenerationState(userId = userId)
    }

  private def initialLibraryRecoFilterForUser(userId: Id[User])(libraryInfo: CuratorLibraryInfo): Boolean = {
    libraryInfo.ownerId != userId && libraryInfo.keepCount > 0 && libraryInfo.visibility != LibraryVisibility.SECRET
    // TODO(josh) more checks (min followers? max age?)
  }

  private def getCandidateLibrariesForUser(userId: Id[User], state: LibraryRecommendationGenerationState): (Seq[LibraryRecoCandidate], SequenceNumber[CuratorLibraryInfo]) = {
    val libs = db.readOnlyReplica { implicit session =>
      libraryInfoRepo.getBySeqNum(state.seq, 200)
    } filter initialLibraryRecoFilterForUser(userId) map { libInfo =>
      LibraryRecoCandidate(userId = userId, libraryInfo = libInfo)
    }

    (libs, libs.lastOption.map(_.libraryInfo.seq).getOrElse(state.seq))
  }

  private def saveLibraryRecommendations(scoredLibraryInfos: Seq[ScoredLibraryInfo], userId: Id[User], newState: LibraryRecommendationGenerationState) =
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

      genStateRepo.save(newState)
    }

  private def processLibraries(candidates: Seq[LibraryRecoCandidate], newState: LibraryRecommendationGenerationState,
    userId: Id[User], alwaysInclude: Set[Id[Library]]): Future[Unit] = {
    scoringHelper(candidates) flatMap { scores =>
      val toBeSaved = scores filter (si => alwaysInclude.contains(si.libraryId) || shouldInclude(si))
      saveLibraryRecommendations(toBeSaved, userId, newState)
      precomputeRecommendationsForUser(userId, alwaysInclude)
    }
  }

  private def precomputeRecommendationsForUser(userId: Id[User], alwaysInclude: Set[Id[Library]]): Future[Unit] = recommendationGenerationLock.withLockFuture {
    if (serviceDiscovery.isLeader()) {
      val state: LibraryRecommendationGenerationState = db.readOnlyReplica { implicit session => getStateOfUser(userId) }
      val (candidateLibraries, newSeqNum) = getCandidateLibrariesForUser(userId, state)

      val newState = state.copy(seq = newSeqNum)
      if (candidateLibraries.isEmpty) {
        db.readWrite { implicit session => genStateRepo.save(newState) }
        if (state.seq < newSeqNum) precomputeRecommendationsForUser(userId, alwaysInclude) else Future.successful()
      } else {
        processLibraries(candidateLibraries, newState, userId, alwaysInclude)
      }
    } else {
      Future.successful()
    }
  }

  def precomputeRecommendations(): Future[Unit] = {
    usersToPrecomputeRecommendationsFor() flatMap { userIds =>
      Future.sequence {
        if (recommendationGenerationLock.waiting < userIds.length + 1) {
          userIds map { userId =>
            val alwaysInclude: Set[Id[Library]] = db.readOnlyReplica { implicit session => libraryRecRepo.getLibraryIdsForUser(userId) }
            precomputeRecommendationsForUser(userId, alwaysInclude)
          }
        } else {
          Seq.empty
        }
      }
    } map (_ => ())
  }

}
