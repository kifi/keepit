package com.keepit.curator.commanders

import com.keepit.curator.model.{
  UriRecommendationStates,
  ScoredSeedItemWithAttribution,
  RecoInfo,
  UserRecommendationGenerationStateRepo,
  UserRecommendationGenerationState,
  Keepers,
  UriRecommendationRepo,
  UriRecommendation,
  UriScores,
  PublicFeedRepo,
  PublicSeedItem,
  SeedItem,
  PublicUriScores,
  PublicFeed,
  PublicScoredSeedItem
}
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ User, ExperimentType, UriRecommendationScores, SystemValueRepo, Name }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.commanders.RemoteUserExperimentCommander

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.collection.concurrent.TrieMap

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    scoringHelper: UriScoringHelper,
    publicScoringHelper: PublicUriScoringHelper,
    uriWeightingHelper: UriWeightingHelper,
    publicUriWeightingHelper: PublicUriWeightingHelper,
    attributionHelper: SeedAttributionHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    uriRecRepo: UriRecommendationRepo,
    publicFeedRepo: PublicFeedRepo,
    genStateRepo: UserRecommendationGenerationStateRepo,
    systemValueRepo: SystemValueRepo,
    experimentCommander: RemoteUserExperimentCommander) {

  val defaultScore = 0.0f
  val recommendationGenerationLock = new ReactiveLock(15)
  val pubicFeedsGenerationLock = new ReactiveLock(1)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()
  private val SEQ_NUM_NAME: Name[SequenceNumber[PublicSeedItem]] = Name("public_feeds_seq_num")

  private def usersToPrecomputeRecommendationsFor(): Future[Seq[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.RECOS_BETA).map(users => users.map(_.id.get).toSeq)

  private def specialCurators(): Future[Seq[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSeq)

  private def computeMasterScore(scores: UriScores): Float = {
    (4 * scores.socialScore +
      6 * scores.overallInterestScore +
      2 * scores.priorScore +
      1 * scores.recencyScore +
      1 * scores.popularityScore +
      9 * scores.recentInterestScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore +
      4 * scores.curationScore.getOrElse(0.0f)) *
      scores.multiplier.getOrElse(1.0f)
  }

  private def computePublicMasterScore(scores: PublicUriScores): Float = {
    (1 * scores.recencyScore +
      1 * scores.popularityScore +
      6 * scores.rekeepScore +
      5 * scores.discoveryScore +
      5 * scores.curationScore.getOrElse(0.0f)) *
      scores.multiplier.getOrElse(1.0f)
  }

  private def computeAdjustedScoreByTester(scoreCoefficients: UriRecommendationScores, scores: UriScores): Float = {
    (scoreCoefficients.recencyScore.getOrElse(defaultScore) * scores.recencyScore +
      scoreCoefficients.overallInterestScore.getOrElse(defaultScore) * scores.overallInterestScore +
      scoreCoefficients.priorScore.getOrElse(defaultScore) * scores.priorScore +
      scoreCoefficients.socialScore.getOrElse(defaultScore) * scores.socialScore +
      scoreCoefficients.popularityScore.getOrElse(defaultScore) * scores.popularityScore +
      scoreCoefficients.recentInterestScore.getOrElse(defaultScore) * scores.recentInterestScore +
      scoreCoefficients.rekeepScore.getOrElse(defaultScore) * scores.rekeepScore +
      scoreCoefficients.discoveryScore.getOrElse(defaultScore) * scores.discoveryScore +
      scoreCoefficients.curationScore.getOrElse(defaultScore) * scores.curationScore.getOrElse(0.0f)) *
      scores.multiplier.getOrElse(1.0f)
  }

  def getTopRecommendations(userId: Id[User], howManyMax: Int): Future[Seq[UriRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      uriRecRepo.getByTopMasterScore(userId, howManyMax)
    }
  }

  def getTopRecommendationsNotPushed(userId: Id[User], howManyMax: Int, masterScoreThreshold: Float = 0f): Future[Seq[UriRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      uriRecRepo.getDigestRecommendableByTopMasterScore(userId, howManyMax, masterScoreThreshold)
    }
  }

  def getAdHocRecommendations(userId: Id[User], howManyMax: Int, scoreCoefficients: UriRecommendationScores): Future[Seq[RecoInfo]] = {
    getTopRecommendations(userId, Math.max(howManyMax, 1000)).map { recos =>
      recos.map { reco =>
        RecoInfo(
          userId = Some(reco.userId),
          uriId = reco.uriId,
          score =
            if (scoreCoefficients.isEmpty) computeMasterScore(reco.allScores)
            else computeAdjustedScoreByTester(scoreCoefficients, reco.allScores),
          explain = Some(reco.allScores.toString),
          attribution = Some(reco.attribution))
      }.sortBy(-1 * _.score).take(howManyMax)
    }
  }

  def getPublicFeeds(howManyMax: Int): Future[Seq[PublicFeed]] = {
    db.readOnlyReplicaAsync { implicit session =>
      publicFeedRepo.getByTopMasterScore(howManyMax)
    }
  }

  private def getPerUserGenerationLock(userId: Id[User]): ReactiveLock = {
    perUserRecommendationGenerationLocks.getOrElseUpdate(userId, new ReactiveLock())
  }

  private def shouldInclude(scores: UriScores): Boolean = { //ZZZ curations score here
    if ((scores.overallInterestScore > 0.4 || scores.recentInterestScore > 0) && computeMasterScore(scores) > 4.5) {
      scores.socialScore > 0.8 ||
        scores.overallInterestScore > 0.65 ||
        scores.priorScore > 0 ||
        (scores.popularityScore > 0.2 && scores.socialScore > 0.65) ||
        scores.recentInterestScore > 0.15 ||
        scores.rekeepScore > 0.3 ||
        scores.discoveryScore > 0.3 ||
        (scores.curationScore.isDefined && (scores.overallInterestScore > 0.5 || scores.recentInterestScore > 0.2))
    } else { //Yes, this could be expressed purly with a logic expression, but I think this is clearer -Stephen
      false
    }
  }

  private def getStateOfUser(userId: Id[User]) =
    db.readOnlyMaster { implicit session =>
      genStateRepo.getByUserId(userId)
    } getOrElse {
      UserRecommendationGenerationState(userId = userId)
    }

  private def getCandidateSeedsForUser(userId: Id[User], state: UserRecommendationGenerationState): Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = {
    val result = for {
      seeds <- seedCommander.getDiscoverableBySeqNumAndUser(state.seq, userId, 200)
      candidateURIs <- shoebox.getCandidateURIs(seeds.map(_.uriId))
    } yield {
      val candidateSeeds = (seeds zip candidateURIs) filter (_._2) map (_._1)
      eliza.checkUrisDiscussed(userId, candidateSeeds.map(_.uriId)).map { checkThreads =>
        val candidates = (candidateSeeds zip checkThreads).collect{case (cand, hasChat) if hasChat => cand}
        (candidates, if (seeds.isEmpty) state.seq else seeds.map(_.seq).max)
      }
    }
    result.flatMap(x => x)
  }

  private def getPublicFeedCandidateSeeds(seq: SequenceNumber[PublicSeedItem]) =
    for {
      seeds <- seedCommander.getBySeqNum(seq, 200)
      candidateURIs <- shoebox.getCandidateURIs(seeds.map { _.uriId })
    } yield {
      ((seeds zip candidateURIs) filter (_._2) map (_._1), if (seeds.isEmpty) seq else seeds.map(_.seq).max)
    }

  private def getRescoreSeedsForUser(userId: Id[User]): Future[Seq[SeedItem]] = {
    for {
      recos <- db.readOnlyReplicaAsync(implicit s => uriRecRepo.getByUserId(userId))
      seeds <- seedCommander.getPreviousSeeds(userId, recos.map(_.uriId))
    } yield {
      seeds
    }
  }

  private def saveScoredSeedItems(items: Seq[ScoredSeedItemWithAttribution], userId: Id[User], newState: UserRecommendationGenerationState) =
    db.readWrite { implicit s =>
      items foreach { item =>
        val recoOpt = uriRecRepo.getByUriAndUserId(item.uriId, userId, None)
        recoOpt.map { reco =>
          uriRecRepo.save(reco.copy(
            state = UriRecommendationStates.ACTIVE,
            masterScore = computeMasterScore(item.uriScores),
            allScores = item.uriScores,
            attribution = item.attribution))
        } getOrElse {
          uriRecRepo.save(UriRecommendation(
            uriId = item.uriId,
            userId = userId,
            masterScore = computeMasterScore(item.uriScores),
            allScores = item.uriScores,
            delivered = 0,
            clicked = 0,
            kept = false,
            trashed = false,
            attribution = item.attribution))
        }
      }

      genStateRepo.save(newState)
    }

  private def processSeeds(seedItems: Seq[SeedItem], newState: UserRecommendationGenerationState,
    userId: Id[User], boostedKeepers: Set[Id[User]]): Future[Boolean] = {
    val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
      seedItem.keepers match {
        case Keepers.ReasonableNumber(users) => !users.contains(userId)
        case _ => false
      }
    }

    val weightedItems = uriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
    val toBeSaved: Future[Seq[ScoredSeedItemWithAttribution]] = scoringHelper(weightedItems, boostedKeepers).map { scoredItems =>
      scoredItems.filter(si => shouldInclude(si.uriScores))
    }.flatMap { scoredItems =>
      attributionHelper.getAttributions(scoredItems)
    }

    toBeSaved.map { items =>
      saveScoredSeedItems(items, userId, newState)
      precomputeRecommendationsForUser(userId, boostedKeepers)
      seedItems.nonEmpty
    }
  }

  private def getPrecomputationRecosResult(seeds: Seq[SeedItem], newSeqNum: SequenceNumber[SeedItem],
    state: UserRecommendationGenerationState, userId: Id[User],
    boostedKeepers: Set[Id[User]]): Future[Boolean] = {
    val newState = state.copy(seq = newSeqNum)
    if (seeds.isEmpty) {
      db.readWrite { implicit session =>
        genStateRepo.save(newState)
      }
      if (state.seq < newSeqNum) { precomputeRecommendationsForUser(userId, boostedKeepers) }
      Future.successful(false)
    } else {
      processSeeds(seeds, newState, userId, boostedKeepers)
    }
  }

  private def precomputeRecommendationsForUser(userId: Id[User], boostedKeepers: Set[Id[User]]): Future[Unit] = recommendationGenerationLock.withLockFuture {
    getPerUserGenerationLock(userId).withLockFuture {
      val state = getStateOfUser(userId)
      val seedsAndSeqFuture = getCandidateSeedsForUser(userId, state)
      val res: Future[Boolean] = seedsAndSeqFuture.flatMap { case (seeds, seq) => getPrecomputationRecosResult(seeds, seq, state, userId, boostedKeepers) }

      res.onFailure {
        case t: Throwable => airbrake.notify("Failure during recommendation precomputation", t)
      }
      res.map(_ => ())
    }
  }

  private def savePublicScoredSeedItems(items: Seq[PublicScoredSeedItem], newSeqNum: SequenceNumber[PublicSeedItem]) =
    db.readWrite { implicit s =>
      items foreach { item =>
        val feedOpt = publicFeedRepo.getByUri(item.uriId, None)
        feedOpt.map { feed =>
          publicFeedRepo.save(feed.copy(
            publicMasterScore = computePublicMasterScore(item.publicUriScores),
            publicAllScores = item.publicUriScores))
        } getOrElse {
          publicFeedRepo.save(PublicFeed(
            uriId = item.uriId,
            publicMasterScore = computePublicMasterScore(item.publicUriScores),
            publicAllScores = item.publicUriScores))
        }
      }
      systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, newSeqNum)
    }

  private def getPrecomputationFeedsResult(publicSeedsAndSeqFuture: Future[(Seq[PublicSeedItem], SequenceNumber[PublicSeedItem])],
    lastSeqNum: SequenceNumber[PublicSeedItem], boostedKeepers: Set[Id[User]]) =
    publicSeedsAndSeqFuture.flatMap {
      case (publicSeedItems, newSeqNum) =>
        if (publicSeedItems.isEmpty) {
          db.readWriteAsync { implicit session =>
            systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, newSeqNum)
          }
          if (lastSeqNum < newSeqNum) precomputePublicFeeds()
          Future.successful(false)
        } else {
          val cleanedItems = publicSeedItems.filter { publicSeedItem => //discard super popular items and the users own keeps
            publicSeedItem.keepers match {
              case Keepers.ReasonableNumber(users) => true
              case _ => false
            }
          }
          val weightedItems = publicUriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
          publicScoringHelper(weightedItems, boostedKeepers).map { items =>
            savePublicScoredSeedItems(items, newSeqNum)
            precomputePublicFeeds()
            publicSeedItems.nonEmpty
          }
        }
    }

  def precomputePublicFeeds(): Future[Unit] = pubicFeedsGenerationLock.withLockFuture {
    specialCurators().flatMap { boostedKeepersSeq =>

      val lastSeqNumFut: Future[SequenceNumber[PublicSeedItem]] = db.readOnlyMasterAsync { implicit session =>
        systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse {
          SequenceNumber[PublicSeedItem](0)
        }
      }

      lastSeqNumFut.flatMap { lastSeqNum =>
        val publicSeedsAndSeqFuture: Future[(Seq[PublicSeedItem], SequenceNumber[PublicSeedItem])] = getPublicFeedCandidateSeeds(lastSeqNum)
        val res: Future[Boolean] = getPrecomputationFeedsResult(publicSeedsAndSeqFuture, lastSeqNum, boostedKeepersSeq.toSet)
        res.map(_ => ())
      }
    }
  }

  def precomputeRecommendations(): Future[Unit] = {
    usersToPrecomputeRecommendationsFor().flatMap { userIds =>
      specialCurators().flatMap { boostedKeepersSeq =>
        if (recommendationGenerationLock.waiting < userIds.length + 1) {
          val boostedKeepers = boostedKeepersSeq.toSet
          Future.sequence(userIds.map(userId => precomputeRecommendationsForUser(userId, boostedKeepers))).map(_ => ())
        } else {
          Future.successful()
        }
      }
    }
  }

  def resetUser(userId: Id[User]): Future[Unit] = {
    getPerUserGenerationLock(userId).withLock {
      db.readWriteAsync { implicit s =>
        val stateOpt = genStateRepo.getByUserId(userId)
        stateOpt.foreach { state =>
          genStateRepo.save(state.copy(seq = SequenceNumber.ZERO))
        }
      }

      val state = getStateOfUser(userId)

      val seedsFuture = getRescoreSeedsForUser(userId)
      specialCurators().flatMap { boostedKeepersSeq =>
        val res: Future[Unit] = seedsFuture.flatMap { seeds =>
          val batches = seeds.grouped(200)
          FutureHelpers.sequentialExec(batches.toIterable)(batch => processSeeds(batch, state, userId, boostedKeepersSeq.toSet))
        }

        res.onFailure {
          case t: Throwable => airbrake.notify("Failure during recommendation precomputation", t)
        }

        res
      }
    }
  }

}
