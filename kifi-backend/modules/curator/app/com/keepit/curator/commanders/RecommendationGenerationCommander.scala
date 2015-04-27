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
import com.keepit.common.zookeeper.{ ServiceDiscovery, ShardingCommander }
import com.keepit.common.time._
import com.keepit.curator.model._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ ExperimentType, NormalizedURI, SystemValueRepo, UriRecommendationScores, User, NotificationCategory }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.PimpMyFuture._
import com.keepit.common.core._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, ElectronicMailCategory }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.statsd.api.Statsd

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Random

import java.util.concurrent.atomic.AtomicInteger

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
    keepRepo: CuratorKeepInfoRepo,
    schedulingProperties: SchedulingProperties,
    shardingCommander: ShardingCommander,
    amazonInstanceInfo: AmazonInstanceInfo,
    systemAdminMailSender: SystemAdminMailSender) extends Logging {

  val defaultScore = 0.0f
  val recommendationGenerationLock = new ReactiveLock(16)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()
  val candidateURILock = new ReactiveLock(4)
  val dbWriteThrottleLock = new ReactiveLock(3)

  val superSpecialLock = new ReactiveLock(1)

  val BATCH_SIZE = 350

  //This method better not be here any more after May 1st 2015 (short term debugging use only!!!)
  private def sendDiagnosticEmail(subject: String, body: String) = {
    systemAdminMailSender.sendMail(
      ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = List(SystemEmailAddress.STEPHEN),
        subject = s"Reco Diagnostics: $subject",
        htmlBody = body,
        category = NotificationCategory.toElectronicMailCategory(NotificationCategory.System.ADMIN)
      )
    )
  }
  private val diagnosticsSentCounter: AtomicInteger = new AtomicInteger(0)

  private def usersToPrecomputeRecommendationsFor(): Seq[Id[User]] = Random.shuffle((seedCommander.getUsersWithSufficientData()).toSeq)

  private def specialCurators(): Future[Set[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.SPECIAL_CURATOR).map(users => users.map(_.id.get).toSet)

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
    val freshnessFactor: Double = 1.0 / Math.log((daysSinceLastKept.toDouble / 42.0) + 2.0)
    val baseScore: Double = 5 * scores.socialScore + 2 * scores.overallInterestScore + scores.libraryInducedScore.getOrElse(scores.overallInterestScore)
    val adaptiveScoreFactor: Double = Math.tanh(numKeepsOfUser.toDouble / 42)
    val adaptiveScore: Double = adaptiveScoreFactor * scores.overallInterestScore + (1.0 - adaptiveScoreFactor) * scores.libraryInducedScore.getOrElse(scores.overallInterestScore)

    (scores.multiplier.getOrElse(1.0f) * interestMatchFactor * freshnessFactor * (baseScore + 2.0 * adaptiveScore)).toFloat + 15.0f //shifting to make comparable to old scoring
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

  private def getCandidateSeedsForUser(userId: Id[User], state: UserRecommendationGenerationState, subsample: Boolean): Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = {
    val timer = new NamedStatsdTimer("RecommendationGenerationCommander.getCandidateSeedsForUser")
    val result = for {
      seeds <- (if (subsample) seedCommander.getPopularDiscoverableBySeqNumAndUser(state.seq, userId, BATCH_SIZE) else seedCommander.getDiscoverableBySeqNumAndUser(state.seq, userId, BATCH_SIZE))
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

  private def saveScoredSeedItems(items: Seq[ScoredSeedItemWithAttribution], userId: Id[User], newState: UserRecommendationGenerationState): Unit = {
    if (items.length > 0) {
      val timer = new NamedStatsdTimer("RecommendationGenerationCommander.saveScoredSeedItems")
      val timerPer = new NamedStatsdTimer("RecommendationGenerationCommander.saveScoredSeedItemsPerItem")
      db.readWrite(attempts = 2) { implicit s =>
        items foreach { item =>

          uriRecRepo.insertOrUpdate(
            UriRecommendation(
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
              topic2 = item.topic2
            )
          )

          if (Random.nextFloat() > 0.75 && diagnosticsSentCounter.getAndIncrement() < 5) {
            val info: String = try {
              uriRecRepo.insertOrUpdate(UriRecommendation(
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
                topic2 = item.topic2
              ))
            } catch {
              case t: Throwable => t.toString
            }
            sendDiagnosticEmail("", info)
          }

        }

        genStateRepo.save(newState)
      }
      timer.stopAndReport()
      timerPer.stopAndReport(items.length)
    }
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

      toBeSaved.flatMap { items =>
        Statsd.gauge("RecosWaitingForSave", items.length, true)
        dbWriteThrottleLock.withLock {
          saveScoredSeedItems(items, userId, newState)
        }.tap(_ => Statsd.gauge(s"${amazonInstanceInfo.name.getOrElse("unknown-instance")}.RecoBatchesWaitingForSave", dbWriteThrottleLock.waiting)).map { _ =>
          Statsd.gauge("RecosWaitingForSave", -1 * items.length, true)
          Statsd.increment("UriRecosGenerated", items.length)
          precomputeRecommendationsForUser(userId, boostedKeepers, Some(alwaysInclude))
          seedItems.nonEmpty
        }
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

      val keepCount = db.readOnlyReplica { implicit session => keepRepo.getKeepCountForUser(userId) }

      val weightedItems: Seq[SeedItemWithMultiplier] = uriWeightingHelper(cleanedItems).filter(_.multiplier != 0.0f)
      val toBeSaved: Future[Seq[(ScoredSeedItemWithAttribution, Float)]] = scoringHelper(weightedItems, boostedKeepers).flatMap { scoredItems =>
        attributionHelper.getAttributions(scoredItems)
      }.map { itemsWithAttribution =>
        itemsWithAttribution.map { item =>
          val daysSinceLastKept: Int = Math.max(Days.daysBetween(originalItems(item.uriId).lastKept, currentDateTime).getDays, 0) //guard agaist keep times in the future (yes!)
          (item, computeMasterScoreNextGen(item.uriScores, daysSinceLastKept, keepCount))
        }
      }

      toBeSaved.flatMap { items =>
        val filteredItems = items.filter { _._2 > 16.0f }
        Statsd.gauge("RecosWaitingForSave", filteredItems.length, true)
        dbWriteThrottleLock.withLock {
          if (filteredItems.length > 0) {
            val timer = new NamedStatsdTimer("RecommendationGenerationCommander.saveScoredSeedItems")
            val timerPer = new NamedStatsdTimer("RecommendationGenerationCommander.saveScoredSeedItemsPerItem")
            db.readWrite(attempts = 2) { implicit s =>
              filteredItems foreach {
                case (item, masterScore) =>
                  uriRecRepo.insertOrUpdate(
                    UriRecommendation(
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
                      topic2 = item.topic2
                    )
                  )
              }

              genStateRepo.save(newState)
            }
            timer.stopAndReport()
            timerPer.stopAndReport(filteredItems.length)
            Statsd.increment("UriRecosGenerated", filteredItems.length)
            Statsd.gauge("RecosWaitingForSave", -1 * filteredItems.length, true)
          }
          precomputeRecommendationsForUser(userId, boostedKeepers, Some(alwaysInclude))
          seedItems.nonEmpty
        }.tap(_ => Statsd.gauge(s"${amazonInstanceInfo.name.getOrElse("unknown-instance")}.RecoBatchesWaitingForSave", dbWriteThrottleLock.waiting))
      }
    }

  private def precomputeRecommendationsForUser(userId: Id[User], boostedKeepers: Set[Id[User]], alwaysIncludeOpt: Option[Set[Id[NormalizedURI]]] = None): Future[Unit] = {
    if (shardingCommander.isRunnerFor(userId.id.toInt) && dbWriteThrottleLock.waiting < 1000) {
      experimentCommander.getExperimentsByUser(userId).flatMap { experiments =>
        val lock = if (experiments.contains(ExperimentType.RECO_FASTLANE)) superSpecialLock else recommendationGenerationLock
        lock.withLockFuture {
          getPerUserGenerationLock(userId).withLockFuture {
            if (shardingCommander.isRunnerFor(userId.id.toInt)) {
              val timer = new NamedStatsdTimer("perItemPerUser")
              val alwaysInclude: Set[Id[NormalizedURI]] = alwaysIncludeOpt.getOrElse {
                if (experiments.contains(ExperimentType.RECO_FASTLANE)) db.readOnlyReplica { implicit session => uriRecRepo.getUriIdsForUser(userId) }
                else db.readOnlyReplica { implicit session => uriRecRepo.getTopUriIdsForUser(userId) }
              }
              val state: UserRecommendationGenerationState = getStateOfUser(userId)
              val seedsAndSeqFuture: Future[(Seq[SeedItem], SequenceNumber[SeedItem])] = getCandidateSeedsForUser(userId, state, experiments.contains(ExperimentType.RECO_SUBSAMPLE))
              val res: Future[Boolean] = seedsAndSeqFuture.flatMap {
                case (seeds, newSeqNum) =>
                  val newState = state.copy(seq = newSeqNum)
                  if (seeds.isEmpty) {
                    db.readWrite { implicit session =>
                      genStateRepo.save(newState)
                    }
                    if (state.seq < newSeqNum) {
                      if (lock.waiting < 50) {
                        precomputeRecommendationsForUser(userId, boostedKeepers, Some(alwaysInclude))
                      } else {
                        precomputeRecommendationsForUser(userId, boostedKeepers) //No point in keeping all that data in memory when it's not needed soon
                      }
                    }
                    Future.successful(false)
                  } else {
                    if (experiments.contains(ExperimentType.NEXT_GEN_RECOS)) processSeedsNextGen(seeds, newState, userId, boostedKeepers, alwaysInclude) else processSeeds(seeds, newState, userId, boostedKeepers, alwaysInclude)
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
    } else {
      Future.successful(())
    }
  }

  def precomputeRecommendations(): Future[Unit] = { //This is the entry point (what the scheduler calls)
    val userIds = usersToPrecomputeRecommendationsFor()
    val t: Future[Future[Unit]] = for {
      boostedKeepersSet <- specialCurators()
    } yield {
      if (recommendationGenerationLock.waiting < userIds.length) {
        Future.sequence(Random.shuffle(userIds).map(userId => precomputeRecommendationsForUser(userId, boostedKeepersSet))).map(_ => ())
      } else {
        Future.successful(())
      }
    }
    t.flatten
  }

  private val candidateUriCache = CacheBuilder.newBuilder()
    .initialCapacity(10000).maximumSize(100000).expireAfterWrite(60L, TimeUnit.MINUTES)
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
