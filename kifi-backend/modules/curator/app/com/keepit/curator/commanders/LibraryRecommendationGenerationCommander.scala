package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.{ State, Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.model._
import com.keepit.curator.{ LibraryQualityHelper, LibraryScoringHelper, ScoredLibraryInfo }
import com.keepit.model.{ LibraryKind, Library, LibraryVisibility, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.Random

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
    libraryQualityHelper: LibraryQualityHelper,
    seedCommander: SeedIngestionCommander,
    serviceDiscovery: ServiceDiscovery) extends Logging {

  val recommendationGenerationLock = new ReactiveLock(8)
  val defaultLibraryScoreParams = LibraryRecoSelectionParams.default

  val MIN_KEEPS_PER_USER = 5

  private val idFilter = new RecoIdFilter[LibraryRecoScore] {}

  private def usersToPrecomputeRecommendationsFor(): Seq[Id[User]] =
    Random.shuffle(seedCommander.getUsersWithSufficientData(MIN_KEEPS_PER_USER).toSeq)

  def getLibraryRecommendations(userId: Id[User], libraryIds: Set[Id[Library]]): Seq[LibraryRecommendation] = {
    db.readOnlyReplica { implicit s => libraryRecRepo.getByLibraryIdsAndUserId(libraryIds, userId) }
  }

  def trackDeliveredRecommendations(userId: Id[User], libraryIds: Set[Id[Library]], source: RecommendationSource, subSource: RecommendationSubSource): Future[Unit] = {
    val recos = getLibraryRecommendations(userId, libraryIds)

    if (recos.size != libraryIds.size) {
      airbrake.notify(s"[trackDeliveredRecommendations] unexpected # of library recommendations; userId=$userId expected=${libraryIds.size} actual=${recos.size} libIds=$libraryIds")
    }

    if (source != RecommendationSource.Admin) SafeFuture {
      analytics.trackDeliveredItems(recos, Some(source), Some(subSource))

      // TODO this can be optimized to only 1 update query but slick doesn't include support for WHERE id IN (...) SQL interpolation
      recos.map { reco => db.readWrite { implicit rw => libraryRecRepo.incrementDeliveredCount(reco.id.get) } }
    }
    else Future.successful(Unit)
  }

  def getTopRecommendations(userId: Id[User], howManyMax: Int, recoSortStrategy: LibraryRecoSelectionStrategy, scoringStrategy: LibraryRecoScoringStrategy, context: Option[String] = None): LibraryRecoResults = {
    def scoreReco(reco: LibraryRecommendation) =
      LibraryRecoScore(scoringStrategy.scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, None, false, 0f), reco)

    val (recos, newContext) = db.readOnlyReplica { implicit session =>
      val recosByTopScore = libraryRecRepo.getRecommendableByTopMasterScore(userId, 1000) map scoreReco
      val finalSorted = recoSortStrategy.sort(recosByTopScore)
      idFilter.take(finalSorted, context, limit = howManyMax)((x: LibraryRecoScore) => x.reco.libraryId.id)
    }

    val recosInfo = recos.map { case LibraryRecoScore(s, r) => LibraryRecommendation.toLibraryRecoInfo(r) }
    LibraryRecoResults(recosInfo, newContext)
  }

  /**
   * @param userIds override the users to generate library recommendations for
   */
  def precomputeRecommendations(userIds: Option[Seq[Id[User]]] = None): Future[Unit] = {
    val usersToPrecomputeFor = userIds getOrElse usersToPrecomputeRecommendationsFor()

    Future.sequence {
      if (recommendationGenerationLock.waiting < usersToPrecomputeFor.length + 1) {
        usersToPrecomputeFor map { userId =>
          statsd.time("curator.libRecosGenForUser", 1) { _ =>
            recommendationGenerationLock.withLockFuture {
              precomputeRecommendationsForUser(userId)
            }
          }
        }
      } else {
        log.info(s"precomputeRecommendations skipping: lock.waiting=${recommendationGenerationLock.waiting} userIds.length=${usersToPrecomputeFor.length}}")
        Seq.empty
      }
    } map (_ => Unit)
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
      if (serviceDiscovery.isRunnerFor(CuratorTasks.libraryRecommendationPrecomputation)) {
        log.info(s"precomputeRecommendationsForUser called userId=$userId seq=${recoGenState.seq}")
        val (candidateLibraries, excludedLibraries, newSeqNum) = getCandidateLibrariesForUser(recoGenState)

        // these libraries were excluded, but it's possible they already exist as library recommendations from a previous precompute
        if (excludedLibraries.nonEmpty) inactivateLibraryRecommendations(excludedLibraries)

        val newState = recoGenState.copy(seq = newSeqNum)
        if (candidateLibraries.isEmpty) {
          val savedNewState = db.readWrite { implicit session => genStateRepo.save(newState) }
          if (recoGenState.seq < newSeqNum) precomputeRecommendationsForUser(alwaysInclude, savedNewState)
          else Future.successful()
        } else processLibraries(candidateLibraries, newState, alwaysInclude)
      } else {
        log.warn("precomputeRecommendationsForUser doing nothing on non-designated machine. Aborting.")
        recommendationGenerationLock.clear()
        Future.successful()
      }
    }

    private def inactivateLibraryRecommendations(libraries: Seq[CuratorLibraryInfo]) = db.readWrite { implicit rw =>
      val libraryIds = libraries.map(_.libraryId).toSet
      val recos = libraryRecRepo.getByLibraryIdsAndUserId(libraryIds, userId, Some(LibraryRecommendationStates.INACTIVE))

      if (recos.nonEmpty) {
        log.info(s"inactivateLibraryRecommendations userId=$userId setting ${recos.size} existing recos to inactive")
        libraryRecRepo.updateLibraryRecommendationState(recos.map(_.id.get), LibraryRecommendationStates.INACTIVE)
      }
    }

    private def isRecommendable(scoredLibrary: ScoredLibraryInfo): Boolean = {
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
        !usersFollowedLibraries.contains(libraryInfo.libraryId) &&
        !libraryQualityHelper.isBadLibraryName(libraryInfo.name)
    }

    private def getCandidateLibrariesForUser(state: LibraryRecommendationGenerationState): (Seq[CuratorLibraryInfo], Seq[CuratorLibraryInfo], SequenceNumber[CuratorLibraryInfo]) = {
      val libs = db.readOnlyReplica { implicit session => libraryInfoRepo.getBySeqNum(state.seq, 200) }
      val (filteredLibs, excludedLibs) = libs partition initialLibraryRecoFilterForUser
      val maxLibSeqNum = libs.lastOption map (_.seq) getOrElse state.seq

      // returns the last seq number we looked at whether we're using it or not, to ensure we don't keep fetching it
      (filteredLibs, excludedLibs, maxLibSeqNum)
    }

    private def saveLibraryRecommendations(scoredLibraryInfos: Seq[ScoredLibraryInfo], state: State[LibraryRecommendation]): Unit =
      db.readWrite { implicit s =>
        scoredLibraryInfos foreach { scoredLibraryInfo =>
          val recoOpt = libraryRecRepo.getByLibraryAndUserId(scoredLibraryInfo.libraryId, userId, None)
          recoOpt.map { reco =>
            libraryRecRepo.save(reco.copy(
              state = state,
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
        val toBeSaved = scores filter (si => alwaysInclude.contains(si.libraryId) || isRecommendable(si))
        saveLibraryRecommendations(toBeSaved, LibraryRecommendationStates.ACTIVE)
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
    // the min(0.01) prevents the algorithm from returning a 0 score if the topic is 0
    val interestPart = (scores.interestScore * selectionParams.interestScoreWeight).min(0.01f)
    val factor = {
      val normalizer = selectionParams.interestScoreWeight
      interestPart / normalizer
    }

    val otherPart = scores.recencyScore * selectionParams.recencyScoreWeight +
      scores.socialScore * selectionParams.socialScoreWeight +
      scores.popularityScore * selectionParams.popularityScoreWeight +
      scores.sizeScore * selectionParams.sizeScoreWeight +
      scores.contentScoreOrDefault * selectionParams.contentScoreWeight

    interestPart + factor * otherPart
  }
}
