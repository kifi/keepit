package com.keepit.curator.commanders

import java.util.concurrent.TimeUnit

import com.google.common.cache.CacheBuilder
import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.curator.model.{ Keepers, RecoInfo, ScoredSeedItemWithAttribution, SeedItem, UriRecommendation, UriRecommendationRepo, UriRecommendationStates, UriScores, UserRecommendationGenerationState, UserRecommendationGenerationStateRepo }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ ExperimentType, NormalizedURI, SystemValueRepo, UriRecommendationScores, User }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Random

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    eliza: ElizaServiceClient,
    scoringHelper: UriScoringHelper,
    publicScoringHelper: PublicUriScoringHelper,
    uriWeightingHelper: UriWeightingHelper,
    attributionHelper: SeedAttributionHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    uriRecRepo: UriRecommendationRepo,
    genStateRepo: UserRecommendationGenerationStateRepo,
    systemValueRepo: SystemValueRepo,
    experimentCommander: RemoteUserExperimentCommander,
    schedulingProperties: SchedulingProperties) extends Logging {

  val defaultScore = 0.0f
  val recommendationGenerationLock = new ReactiveLock(6)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()
  val candidateURILock = new ReactiveLock(4)

  val superSpecialUsers = Seq(Id[User](273))
  val superSpecialLock = new ReactiveLock(1)

  val BATCH_SIZE = 200

  private def usersToPrecomputeRecommendationsFor(): Seq[Id[User]] = Random.shuffle((seedCommander.getUsersWithSufficientData()).toSeq)

  private def specialCurators(): Future[Seq[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSeq)

  private def computeMasterScore(scores: UriScores): Float = {
    (4 * scores.socialScore +
      5 * scores.overallInterestScore +
      2 * scores.priorScore +
      1 * scores.recencyScore +
      1 * scores.popularityScore +
      7 * scores.recentInterestScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore +
      4 * scores.curationScore.getOrElse(0.0f) +
      4 * scores.libraryInducedScore.getOrElse(0.0f)) *
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

  private def getPerUserGenerationLock(userId: Id[User]): ReactiveLock = {
    perUserRecommendationGenerationLocks.getOrElseUpdate(userId, new ReactiveLock(1))
  }

  private def shouldInclude(scores: UriScores): Boolean = {
    if (((scores.overallInterestScore > 0.4 || scores.recentInterestScore > 0) && computeMasterScore(scores) > 6) || (scores.libraryInducedScore.isDefined && computeMasterScore(scores) > 2)) {
      scores.socialScore > 0.8 ||
        scores.overallInterestScore > 0.65 ||
        scores.priorScore > 0 ||
        (scores.popularityScore > 0.2 && scores.socialScore > 0.65) ||
        scores.recentInterestScore > 0.15 ||
        scores.rekeepScore > 0.3 ||
        scores.discoveryScore > 0.3 ||
        scores.libraryInducedScore.isDefined ||
        (scores.curationScore.isDefined && (scores.overallInterestScore > 0.5 || scores.recentInterestScore > 0.2))
    } else { //Yes, this could be expressed purly with a logic expression, but I think this is clearer -Stephen
      false
    }
  }

  private def getStateOfUser(userId: Id[User]): UserRecommendationGenerationState =
    db.readOnlyMaster { implicit session =>
      genStateRepo.getByUserId(userId)
    } getOrElse {
      UserRecommendationGenerationState(userId = userId)
    }

  private def getCandidateSeedsForUser(userId: Id[User], state: UserRecommendationGenerationState): Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = {
    val timer = new NamedStatsdTimer("RecommendationGenerationCommander.getCandidateSeedsForUser")
    val result = for {
      seeds <- seedCommander.getDiscoverableBySeqNumAndUser(state.seq, userId, BATCH_SIZE)
      candidateURIs <- getCandidateURIs(seeds.map(_.uriId))
    } yield {
      val candidateSeeds = (seeds zip candidateURIs) filter (_._2) map (_._1)
      eliza.checkUrisDiscussed(userId, candidateSeeds.map(_.uriId)).map { checkThreads =>
        timer.stopAndReport()
        val candidates = (candidateSeeds zip checkThreads).collect { case (cand, hasChat) if !hasChat => cand }
        (candidates, if (seeds.isEmpty) state.seq else seeds.map(_.seq).max)
      }
    }
    result.flatMap(x => x)
  }

  private def saveScoredSeedItems(items: Seq[ScoredSeedItemWithAttribution], userId: Id[User], newState: UserRecommendationGenerationState) = {
    val timer = new NamedStatsdTimer("RecommendationGenerationCommander.saveScoredSeedItems")
    db.readWrite(attempts = 2) { implicit s =>
      items foreach { item =>
        val recoOpt = uriRecRepo.getByUriAndUserId(item.uriId, userId, None)
        recoOpt.map { reco =>
          uriRecRepo.save(reco.copy(
            state = UriRecommendationStates.ACTIVE,
            masterScore = computeMasterScore(item.uriScores),
            allScores = item.uriScores,
            attribution = item.attribution,
            topic1 = item.topic1,
            topic2 = item.topic2))
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
            attribution = item.attribution,
            topic1 = item.topic1,
            topic2 = item.topic2))
        }
      }

      genStateRepo.save(newState)
    }
    timer.stopAndReport()
  }

  private def processSeeds(
    seedItems: Seq[SeedItem],
    newState: UserRecommendationGenerationState,
    userId: Id[User],
    boostedKeepers: Set[Id[User]],
    alwaysInclude: Set[Id[NormalizedURI]]): Future[Boolean] =
    {
      val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
        seedItem.keepers match {
          case Keepers.ReasonableNumber(users) => !users.contains(userId)
          case _ => false
        }
      }

      val weightedItems = uriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
      val toBeSaved: Future[Seq[ScoredSeedItemWithAttribution]] = scoringHelper(weightedItems, boostedKeepers).map { scoredItems =>
        scoredItems.filter(si => alwaysInclude.contains(si.uriId) || shouldInclude(si.uriScores))
      }.flatMap { scoredItems =>
        attributionHelper.getAttributions(scoredItems)
      }

      toBeSaved.map { items =>
        saveScoredSeedItems(items, userId, newState)
        precomputeRecommendationsForUser(userId, boostedKeepers, Some(alwaysInclude))
        seedItems.nonEmpty
      }
    }

  private def precomputeRecommendationsForUser(userId: Id[User], boostedKeepers: Set[Id[User]], alwaysIncludeOpt: Option[Set[Id[NormalizedURI]]] = None): Future[Unit] = {
    val lock = if (superSpecialUsers.contains(userId)) superSpecialLock else recommendationGenerationLock
    lock.withLockFuture {
      getPerUserGenerationLock(userId).withLockFuture {
        if (schedulingProperties.isRunnerFor(CuratorTasks.uriRecommendationPrecomputation)) {
          val timer = new NamedStatsdTimer("perItemPerUser")
          val alwaysInclude: Set[Id[NormalizedURI]] = alwaysIncludeOpt.getOrElse {
            if (superSpecialUsers.contains(userId)) db.readOnlyReplica { implicit session => uriRecRepo.getTopUriIdsForUser(userId) }
            else db.readOnlyReplica { implicit session => uriRecRepo.getUriIdsForUser(userId) }
          }
          val state: UserRecommendationGenerationState = getStateOfUser(userId)
          val seedsAndSeqFuture: Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = getCandidateSeedsForUser(userId, state)
          val res: Future[Boolean] = seedsAndSeqFuture.flatMap {
            case (seeds, newSeqNum) =>
              val newState = state.copy(seq = newSeqNum)
              if (seeds.isEmpty) {
                db.readWrite { implicit session =>
                  genStateRepo.save(newState)
                }
                if (state.seq < newSeqNum) {
                  if (lock.waiting < 200) {
                    precomputeRecommendationsForUser(userId, boostedKeepers, Some(alwaysInclude))
                  } else {
                    precomputeRecommendationsForUser(userId, boostedKeepers) //No point in keeping all that data in memory when it's not needed soon
                  }
                }
                Future.successful(false)
              } else {
                processSeeds(seeds, newState, userId, boostedKeepers, alwaysInclude)
              }
          }
          res.onSuccess {
            case _ => timer.stopAndReport(BATCH_SIZE.toDouble)
          }

          res.onFailure {
            case t: Throwable => airbrake.notify("Failure during recommendation precomputation", t)
          }
          res.map(_ => ())
        } else {
          log.warn("Trying to run reco precomputation on non-designated machine. Aborting.")
          recommendationGenerationLock.clear()
          Future.successful(())
        }
      }
    }
  }

  def precomputeRecommendations(): Future[Unit] = {
    val userIds = usersToPrecomputeRecommendationsFor()
    specialCurators().flatMap { boostedKeepersSeq =>
      if (recommendationGenerationLock.waiting < userIds.length + 1) {
        val boostedKeepers = boostedKeepersSeq.toSet
        Future.sequence(userIds.map(userId => precomputeRecommendationsForUser(userId, boostedKeepers))).map(_ => ())
      } else {
        Future.successful(())
      }
    }
  }

  private val candidateUriCache = CacheBuilder.newBuilder()
    .initialCapacity(100000).maximumSize(1000000).expireAfterWrite(24L, TimeUnit.HOURS)
    .build[Id[NormalizedURI], java.lang.Boolean]

  private def getCandidateURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = candidateURILock.withLockFuture {
    val uriIdIndexes: Map[Id[NormalizedURI], Int] = (for { i <- 0 until uriIds.size } yield uriIds(i) -> i).toMap

    val fromCache: Map[Id[NormalizedURI], Boolean] = candidateUriCache.getAllPresent(uriIds).map {
      case (id, bool) => (id, bool.booleanValue) // convert from Java Boolean to Scala Boolean
    }.toMap
    val notFromCache = uriIds diff fromCache.keys.toSeq

    val candidatesF: Future[Seq[Boolean]] =
      if (notFromCache.nonEmpty) shoebox.getCandidateURIs(notFromCache) else Future.successful(Seq.empty)

    candidatesF map { candidates =>
      val notFromCacheResults = (notFromCache zip candidates).map {
        case (id, bool) =>
          candidateUriCache.put(id, bool)
          (id, bool)
      }.toMap

      // puts the candidate booleans back in the order they were passed in
      val results = Array.fill[Boolean](uriIds.size)(false)
      (fromCache ++ notFromCacheResults).foreach {
        case (id, bool) => results.update(uriIdIndexes(id), bool)
      }
      results.toSeq
    }
  }

}
