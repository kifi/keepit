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
import com.keepit.common.time._
import com.keepit.curator.model._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ ExperimentType, NormalizedURI, SystemValueRepo, UriRecommendationScores, User }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.statsd.api.Statsd

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Random

import org.joda.time.{ DateTime, Days }

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
  val recommendationGenerationLock = new ReactiveLock(8)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()
  val candidateURILock = new ReactiveLock(4)

  val superSpecialUsers = Seq(Id[User](273))
  val superSpecialLock = new ReactiveLock(1)

  val BATCH_SIZE = 350

  private def usersToPrecomputeRecommendationsFor(): Seq[Id[User]] = Random.shuffle((seedCommander.getUsersWithSufficientData()).toSeq)

  private def specialCurators(): Future[Set[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSet)

  private def nextGenUsers(): Future[Set[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.NEXT_GEN_RECOS).map(users => users.map(_.id.get).toSet)

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

  private def computeMasterScoreNextGen(scores: UriScores, daysSinceLastKept: Int, numKeepsOfUser: Int): Float = {
    val interestMatchFactor: Double = Math.max(0, Math.max(scores.overallInterestScore - 0.3, scores.libraryInducedScore.map(_ - 0.5).getOrElse(0.0)))
    val freshnessFactor: Double = 1.0 / Math.log((numKeepsOfUser.toDouble / 42.0) + 2.0)
    val baseScore: Double = 5 * scores.socialScore + 2 * scores.overallInterestScore + scores.libraryInducedScore.getOrElse(scores.overallInterestScore)
    val adaptiveScoreFactor: Double = Math.tanh(numKeepsOfUser.toDouble / 42)
    val adaptiveScore: Double = adaptiveScoreFactor * scores.overallInterestScore + (1.0 - adaptiveScoreFactor) * scores.libraryInducedScore.getOrElse(scores.overallInterestScore)

    (scores.multiplier.getOrElse(1.0f) * interestMatchFactor * freshnessFactor * (baseScore + 2.0 * adaptiveScore)).toFloat
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

  private def getCandidateSeedsForUser(userId: Id[User], state: UserRecommendationGenerationState, nextGen: Boolean): Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = {
    val timer = new NamedStatsdTimer("RecommendationGenerationCommander.getCandidateSeedsForUser")
    val result = for {
      seeds <- if (nextGen) seedCommander.getPopularDiscoverableBySeqNumAndUser(state.seq, userId, BATCH_SIZE) else seedCommander.getDiscoverableBySeqNumAndUser(state.seq, userId, BATCH_SIZE)
      candidateURIs <- getCandidateURIs(seeds.map(_.uriId))
    } yield {
      val candidateSeeds = (seeds zip candidateURIs) filter (_._2) map (_._1)
      val timer2 = new NamedStatsdTimer("RecommendationGenerationCommander.checkUrisDiscussed")
      eliza.checkUrisDiscussed(userId, candidateSeeds.map(_.uriId)).map { checkThreads =>
        timer.stopAndReport()
        timer2.stopAndReport()
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
        Statsd.increment("UriRecosGenerated", items.length)
        saveScoredSeedItems(items, userId, newState)
        precomputeRecommendationsForUser(userId, boostedKeepers, false, Some(alwaysInclude))
        seedItems.nonEmpty
      }
    }

  private def processSeedsNextGen(
    seedItems: Seq[SeedItem],
    newState: UserRecommendationGenerationState,
    userId: Id[User],
    boostedKeepers: Set[Id[User]],
    alwaysInclude: Set[Id[NormalizedURI]]): Future[Boolean] =
    {
      val cleanedItems: Seq[SeedItem] = seedItems.filter { seedItem => //discard super popular items and the users own keeps
        seedItem.keepers match {
          case Keepers.ReasonableNumber(users) => !users.contains(userId)
          case _ => false
        }
      }

      val originalItems: Map[Id[NormalizedURI], SeedItem] = cleanedItems.map { item => (item.uriId, item) }.toMap

      val weightedItems: Seq[SeedItemWithMultiplier] = uriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
      val toBeSaved: Future[Seq[(ScoredSeedItemWithAttribution, Float)]] = scoringHelper(weightedItems, boostedKeepers).flatMap { scoredItems =>
        attributionHelper.getAttributions(scoredItems)
      }.map { itemsWithAttribution =>
        itemsWithAttribution.map { item =>
          val daysSinceLastKept: Int = Math.max(Days.daysBetween(originalItems(item.uriId).lastKept, currentDateTime).getDays, 0) //guard agaist keep times in the future (yes!)
          (item, computeMasterScoreNextGen(item.uriScores, daysSinceLastKept, 4242))
        }
      }

      toBeSaved.map { items =>
        Statsd.increment("UriRecosGeneratedNextGen", items.length)

        db.readWrite(attempts = 2) { implicit s =>
          items foreach {
            case (item, masterScore) =>
              if (masterScore > 0.5) {
                val recoOpt = uriRecRepo.getByUriAndUserId(item.uriId, userId, None)
                recoOpt.map { reco =>
                  uriRecRepo.save(reco.copy(
                    state = UriRecommendationStates.ACTIVE,
                    masterScore = masterScore,
                    allScores = item.uriScores,
                    attribution = item.attribution,
                    topic1 = item.topic1,
                    topic2 = item.topic2))
                } getOrElse {
                  uriRecRepo.save(UriRecommendation(
                    uriId = item.uriId,
                    userId = userId,
                    masterScore = masterScore,
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
          }

          genStateRepo.save(newState)
        }

        precomputeRecommendationsForUser(userId, boostedKeepers, true, Some(alwaysInclude))
        seedItems.nonEmpty
      }
    }

  private def precomputeRecommendationsForUser(userId: Id[User], boostedKeepers: Set[Id[User]], nextGen: Boolean, alwaysIncludeOpt: Option[Set[Id[NormalizedURI]]] = None): Future[Unit] = {
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
          val seedsAndSeqFuture: Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = getCandidateSeedsForUser(userId, state, nextGen)
          val res: Future[Boolean] = seedsAndSeqFuture.flatMap {
            case (seeds, newSeqNum) =>
              val newState = state.copy(seq = newSeqNum)
              if (seeds.isEmpty) {
                db.readWrite { implicit session =>
                  genStateRepo.save(newState)
                }
                if (state.seq < newSeqNum) {
                  if (lock.waiting < 200) {
                    precomputeRecommendationsForUser(userId, boostedKeepers, nextGen, Some(alwaysInclude))
                  } else {
                    precomputeRecommendationsForUser(userId, boostedKeepers, nextGen) //No point in keeping all that data in memory when it's not needed soon
                  }
                }
                Future.successful(false)
              } else {
                if (nextGen) processSeeds(seeds, newState, userId, boostedKeepers, alwaysInclude) else processSeeds(seeds, newState, userId, boostedKeepers, alwaysInclude)
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

  def precomputeRecommendations(): Future[Unit] = { //This is the entry point (what the scheduler calls)
    val userIds = usersToPrecomputeRecommendationsFor()
    for {
      boostedKeepersSet <- specialCurators()
      nextGenUsersSet <- nextGenUsers()
    } yield {
      if (recommendationGenerationLock.waiting < userIds.length + 1) {
        Future.sequence(userIds.map(userId => precomputeRecommendationsForUser(userId, boostedKeepersSet, nextGenUsersSet contains userId))).map(_ => ())
      } else {
        Future.successful(())
      }
    }
  }

  private val candidateUriCache = CacheBuilder.newBuilder()
    .initialCapacity(100000).maximumSize(1000000).expireAfterWrite(24L, TimeUnit.HOURS)
    .build[Id[NormalizedURI], java.lang.Boolean]

  private def getCandidateURIs(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    val timer = new NamedStatsdTimer("RecommendationGenerationCommander.getCandidateURIs")
    candidateURILock.withLockFuture {
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
        timer.stopAndReport()
        results.toSeq
      }
    }
  }

}
