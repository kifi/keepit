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
import com.keepit.model.{ LibraryKind, ExperimentType, Library, LibraryVisibility, User }
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
    analytics: CuratorAnalytics,
    serviceDiscovery: ServiceDiscovery) extends Logging {

  val recommendationGenerationLock = new ReactiveLock(8)
  val defaultLibraryScoreParams = LibraryRecoSelectionParams.default

  private def usersToPrecomputeRecommendationsFor(): Future[Seq[Id[User]]] =
    experimentCommander.getUsersByExperiment(ExperimentType.CURATOR_LIBRARY_RECOS).
      map(users => users.map(_.id.get).toSeq)

  def getTopRecommendations(userId: Id[User], howManyMax: Int, source: RecommendationSource, subSource: RecommendationSubSource,
    recoSortStrategy: LibraryRecoSelectionStrategy, scoringStrategy: LibraryRecoScoringStrategy): Seq[LibraryRecoInfo] = {
    def scoreReco(reco: LibraryRecommendation) =
      LibraryRecoScore(scoringStrategy.scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, None, false, 0f), reco)

    val recos = db.readOnlyReplica { implicit session =>
      val recosByTopScore = libraryRecRepo.getRecommendableByTopMasterScore(userId, 1000) map scoreReco
      recoSortStrategy.sort(recosByTopScore) take howManyMax
    }

    if (source != RecommendationSource.Admin) SafeFuture {
      db.readWrite { implicit session =>
        recos.map { recoScore => libraryRecRepo.incrementDeliveredCount(recoScore.reco.id.get) }
        analytics.trackDeliveredItems(recos.map(_.reco), Some(source), Some(subSource))
      }
    }

    recos.map { case LibraryRecoScore(s, r) => LibraryRecommendation.toLibraryRecoInfo(r) }
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
      scoredLibrary.masterScore > 1 // TODO(josh) improve this cutoff
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
        libraryInfo.kind == LibraryKind.USER_CREATED &&
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

case class LibraryRecoScore(score: Float, reco: LibraryRecommendation)

trait LibraryRecoSelectionStrategy {
  def sort(recosByTopScore: Seq[LibraryRecoScore]): Seq[LibraryRecoScore]
}

class TopScoreLibraryRecoSelectionStrategy(val minScore: Float = 0f) extends LibraryRecoSelectionStrategy {
  def sort(recosByTopScore: Seq[LibraryRecoScore]) =
    recosByTopScore filter (_.score > minScore) sortBy (-_.score)
}

trait LibraryRecoScoringStrategy {
  def scoreItem(masterScore: Float, scores: LibraryScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    val basePenaltyFactor = Math.pow(0.97, timesDelivered) * Math.pow(0.8, timesClicked)
    val votePenaltyFactor = goodBad.map { vote => if (vote) 0.97 else 0.5 }.getOrElse(1.0)
    val finalPenaltyFactor = Math.pow(basePenaltyFactor * votePenaltyFactor, if (heavyPenalty) 5 else 1)
    val adjustedScore = masterScore + recencyWeight * scores.recencyScore
    (masterScore * finalPenaltyFactor).toFloat
  }
}

class DefaultLibraryRecoScoringStrategy extends LibraryRecoScoringStrategy

class NonLinearLibraryRecoScoringStrategy(selectionParams: LibraryRecoSelectionParams) extends LibraryRecoScoringStrategy {

  override def scoreItem(masterScore: Float, scores: LibraryScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    super.scoreItem(recomputeScore(scores), scores, timesDelivered, timesClicked, goodBad, heavyPenalty, recencyWeight)
  }

  private def recomputeScore(scores: LibraryScores): Float = {
    val interestPart = (scores.interestScore * selectionParams.interestScoreWeight)
    val factor = {
      val normalizer = selectionParams.interestScoreWeight
      interestPart / normalizer
    }

    val socialPart = (
      scores.recencyScore * selectionParams.recencyScoreWeight +
      scores.socialScore * selectionParams.socialScoreWeight +
      scores.popularityScore * selectionParams.popularityScoreWeight +
      scores.sizeScore * selectionParams.sizeScoreWeight
    )

    (interestPart + factor * socialPart)
  }
}
