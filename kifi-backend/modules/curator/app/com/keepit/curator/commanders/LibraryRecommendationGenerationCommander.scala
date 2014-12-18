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
import com.keepit.curator.model._
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
    libMembershipRepo: CuratorLibraryMembershipInfoRepo,
    experimentCommander: RemoteUserExperimentCommander,
    serviceDiscovery: ServiceDiscovery) extends Logging {

  val recommendationGenerationLock = new ReactiveLock(8)
  val defaultLibraryScoreParams = LibraryRecoSelectionParams.default

  private def usersToPrecomputeRecommendationsFor(): Future[Seq[Id[User]]] =
    experimentCommander.getUsersByExperiment(ExperimentType.CURATOR_LIBRARY_RECOS).
      map(users => users.map(_.id.get).toSeq)

  def getTopRecommendations(userId: Id[User], howManyMax: Int): Future[Seq[LibraryRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      libraryRecRepo.getRecommendableByTopMasterScore(userId, howManyMax)
    }
  }

  /**
   * @param userIds override the users to generate library recommendations for
   */
  def precomputeRecommendations(userIds: Option[Seq[Id[User]]] = None): Future[Unit] = {
    val usersToPrecomputeForF = userIds.map { ids => Future.successful(ids) } getOrElse usersToPrecomputeRecommendationsFor()

    usersToPrecomputeForF flatMap { userIds =>
      Future.sequence {
        if (recommendationGenerationLock.waiting < userIds.length + 1) {
          userIds map { userId =>
            statsd.time("curator.libRecosGenForUser", 1) { _ =>
              recommendationGenerationLock.withLockFuture {
                precomputeRecommendationsForUser(userId)
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

  // public for admin/adhoc purpose only
  def precomputeRecommendationsForUser(userId: Id[User], selectionParams: Option[LibraryRecoSelectionParams] = None): Future[Unit] = {
    val recoGenForUser = LibraryRecoGenerationForUserHelper(userId, selectionParams getOrElse defaultLibraryScoreParams)
    new SafeFuture(recoGenForUser.precomputeRecommendations())
  }

  case class LibraryRecoGenerationForUserHelper(userId: Id[User], selectionParams: LibraryRecoSelectionParams) {

    lazy val usersFollowedLibraries: Set[Id[Library]] = db.readOnlyReplica { implicit s => libMembershipRepo.getLibrariesByUserId(userId).toSet }

    def precomputeRecommendations() = {
      val state: LibraryRecommendationGenerationState = db.readOnlyReplica { implicit session =>
        val state = getStateOfUser()
        if (state.id.exists(_ => selectionParams.reset)) state.copy(seq = SequenceNumber.ZERO) else state
      }
      val alwaysInclude: Set[Id[Library]] = db.readOnlyReplica { implicit session => libraryRecRepo.getLibraryIdsForUser(userId) }
      precomputeRecommendationsForUser(alwaysInclude, state)
    }

    private def precomputeRecommendationsForUser(alwaysInclude: Set[Id[Library]], recoGenState: LibraryRecommendationGenerationState): Future[Unit] = {
      if (serviceDiscovery.isLeader()) {
        log.info(s"precomputeRecommendationsForUser called userId=$userId seq=${recoGenState.seq}")
        val (candidateLibraries, newSeqNum) = getCandidateLibrariesForUser(recoGenState)

        val newState = recoGenState.copy(seq = newSeqNum)
        if (candidateLibraries.isEmpty) {
          val savedNewState = db.readWrite { implicit session => genStateRepo.save(newState) }
          if (recoGenState.seq < newSeqNum) precomputeRecommendationsForUser(alwaysInclude, savedNewState)
          else Future.successful()
        } else processLibraries(candidateLibraries, newState, alwaysInclude)
      } else {
        log.warn("precomputeRecommendationsForUser doing nothing on non-leader")
        Future.successful()
      }
    }

    private def shouldInclude(scoredLibrary: ScoredLibraryInfo): Boolean = {
      true // TODO(josh)
    }

    private def getStateOfUser(): LibraryRecommendationGenerationState = {
      db.readOnlyMaster { implicit session =>
        genStateRepo.getByUserId(userId)
      } getOrElse {
        LibraryRecommendationGenerationState(userId = userId)
      }
    }

    private def initialLibraryRecoFilterForUser(libraryInfo: CuratorLibraryInfo): Boolean = {
      libraryInfo.ownerId != userId && libraryInfo.visibility != LibraryVisibility.SECRET &&
        libraryInfo.keepCount >= selectionParams.minKeeps &&
        libraryInfo.memberCount >= selectionParams.minMembers &&
        !usersFollowedLibraries.contains(libraryInfo.libraryId)
    }

    private def getCandidateLibrariesForUser(state: LibraryRecommendationGenerationState): (Seq[CuratorLibraryInfo], SequenceNumber[CuratorLibraryInfo]) = {
      val libs = db.readOnlyReplica { implicit session => libraryInfoRepo.getBySeqNum(state.seq, 200) }
      val filteredLibs = libs filter initialLibraryRecoFilterForUser
      val maxLibSeqNum = libs.lastOption map (_.seq) getOrElse state.seq

      // returns the last seq number we looked at whether we're using it or not, to ensure we don't keep fetching it
      (filteredLibs, maxLibSeqNum)
    }

    private def saveLibraryRecommendations(scoredLibraryInfos: Seq[ScoredLibraryInfo]): Unit =
      db.readWrite { implicit s =>
        scoredLibraryInfos foreach { scoredLibraryInfo =>
          val recoOpt = libraryRecRepo.getByLibraryAndUserId(scoredLibraryInfo.libraryId, userId, None)
          recoOpt.map { reco =>
            libraryRecRepo.save(reco.copy(
              state = LibraryRecommendationStates.ACTIVE,
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
      alwaysInclude: Set[Id[Library]]): Future[Unit] = {
      scoringHelper(userId, candidates, selectionParams) flatMap { scores =>
        val toBeSaved = scores filter (si => alwaysInclude.contains(si.libraryId) || shouldInclude(si))
        saveLibraryRecommendations(toBeSaved)
        precomputeRecommendationsForUser(alwaysInclude, newState)
      }
    }

  }

}
