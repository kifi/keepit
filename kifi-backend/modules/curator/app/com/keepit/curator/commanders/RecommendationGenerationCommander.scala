package com.keepit.curator.commanders

import com.keepit.curator.model.{
  ScoredSeedItemWithAttribution,
  RecommendationInfo,
  UserRecommendationGenerationStateRepo,
  UserRecommendationGenerationState,
  Keepers,
  UriRecommendationRepo,
  UriRecommendation,
  UriScores
}
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.ReactiveLock
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
    scoringHelper: UriScoringHelper,
    uriWeightingHelper: UriWeightingHelper,
    attributionHelper: SeedAttributionHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    uriRecRepo: UriRecommendationRepo,
    genStateRepo: UserRecommendationGenerationStateRepo,
    experimentCommander: RemoteUserExperimentCommander) {

  val defaultScore = 0.0f

  val recommendationGenerationLock = new ReactiveLock(15)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()

  private def usersToPrecomputeRecommendationsFor(): Future[Seq[Id[User]]] = experimentCommander.getUsersByExperiment(ExperimentType.RECOS_BETA).map(users => users.map(_.id.get).toSeq)

  private def computeMasterScore(scores: UriScores): Float = {
    (5 * scores.socialScore +
      6 * scores.overallInterestScore +
      2 * scores.priorScore +
      1 * scores.recencyScore +
      1 * scores.popularityScore +
      9 * scores.recentInterestScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore) *
      scores.multiplier
  }

  private def computeAdjustedScoreByTester(scoreCoefficients: UriRecommendationScores, scores: UriScores): Float = {
    (scoreCoefficients.recencyScore.getOrElse(defaultScore) * scores.recencyScore +
      scoreCoefficients.overallInterestScore.getOrElse(defaultScore) * scores.overallInterestScore +
      scoreCoefficients.priorScore.getOrElse(defaultScore) * scores.priorScore +
      scoreCoefficients.socialScore.getOrElse(defaultScore) * scores.socialScore +
      scoreCoefficients.popularityScore.getOrElse(defaultScore) * scores.popularityScore +
      scoreCoefficients.recentInterestScore.getOrElse(defaultScore) * scores.recentInterestScore +
      scoreCoefficients.rekeepScore.getOrElse(defaultScore) * scores.rekeepScore +
      scoreCoefficients.discoveryScore.getOrElse(defaultScore) * scores.discoveryScore) *
      scores.multiplier
  }

  def getTopRecommendations(userId: Id[User], howManyMax: Int): Future[Seq[UriRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      uriRecRepo.getByTopMasterScore(userId, howManyMax)
    }
  }

  def getTopRecommendationsNotPushed(userId: Id[User], howManyMax: Int): Future[Seq[UriRecommendation]] = {
    db.readOnlyReplicaAsync { implicit session =>
      uriRecRepo.getNotPushedByTopMasterScore(userId, howManyMax)
    }
  }

  def getAdHocRecommendations(userId: Id[User], howManyMax: Int, scoreCoefficients: UriRecommendationScores): Future[Seq[RecommendationInfo]] = {
    getTopRecommendations(userId, Math.max(howManyMax, 1000)).map { recos =>
      recos.map { reco =>
        RecommendationInfo(
          userId = reco.userId,
          uriId = reco.uriId,
          score =
            if (scoreCoefficients.isEmpty) computeMasterScore(reco.allScores)
            else computeAdjustedScoreByTester(scoreCoefficients, reco.allScores),
          explain = Some(reco.allScores.toString)
        )
      }.sortBy(-1 * _.score).take(howManyMax)
    }
  }

  private def getPerUserGenerationLock(userId: Id[User]): ReactiveLock = {
    perUserRecommendationGenerationLocks.getOrElseUpdate(userId, new ReactiveLock())
  }

  private def shouldInclude(scores: UriScores): Boolean = {
    if (scores.overallInterestScore > 0.4 || scores.recentInterestScore > 0) {
      scores.socialScore > 0.8 ||
        scores.overallInterestScore > 0.65 ||
        scores.priorScore > 0 ||
        (scores.popularityScore > 0.2 && scores.socialScore > 0.4) ||
        scores.recentInterestScore > 0.15 ||
        scores.rekeepScore > 0.3 ||
        scores.discoveryScore > 0.3
    } else { //Yes, this could be expressed purly with a logic expression, but I think this is clearer -Stephen
      false
    }
  }

  private def precomputeRecommendationsForUser(userId: Id[User]): Unit = recommendationGenerationLock.withLockFuture {
    getPerUserGenerationLock(userId).withLockFuture {
      val state = db.readOnlyMaster { implicit session =>
        genStateRepo.getByUserId(userId)
      } getOrElse {
        UserRecommendationGenerationState(userId = userId)
      }
      val seedsAndSeqFuture = for {
        seeds <- seedCommander.getBySeqNumAndUser(state.seq, userId, 200)
        discoverableSeeds = seeds.filter(_.discoverable)
        candidateURIs <- shoebox.getCandidateURIs(discoverableSeeds.map { _.uriId })
      } yield {
        ((discoverableSeeds zip candidateURIs) filter (_._2) map (_._1), if (seeds.isEmpty) state.seq else seeds.map(_.seq).max)
      }

      val res: Future[Boolean] = seedsAndSeqFuture.flatMap {
        case (seedItems, newSeqNum) =>
          val newState = state.copy(seq = newSeqNum)
          if (seedItems.isEmpty) {
            db.readWrite { implicit session =>
              genStateRepo.save(newState)
            }
            if (state.seq < newSeqNum) { precomputeRecommendationsForUser(userId) }
            Future.successful(false)
          } else {
            val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
              seedItem.keepers match {
                case Keepers.ReasonableNumber(users) => !users.contains(userId)
                case _ => false
              }
            }
            val weightedItems = uriWeightingHelper(cleanedItems)
            val toBeSaved: Future[Seq[ScoredSeedItemWithAttribution]] = scoringHelper(weightedItems).map { scoredItems =>
              scoredItems.filter(si => shouldInclude(si.uriScores))
            }.flatMap { scoredItems =>
              attributionHelper.getAttributions(scoredItems)
            }

            toBeSaved.map { items =>
              db.readWrite { implicit s =>
                items foreach { item =>
                  val recoOpt = uriRecRepo.getByUriAndUserId(item.uriId, userId, None)
                  recoOpt.map { reco =>
                    uriRecRepo.save(reco.copy(
                      masterScore = computeMasterScore(item.uriScores),
                      allScores = item.uriScores,
                      attribution = item.attribution
                    ))
                  } getOrElse {
                    uriRecRepo.save(UriRecommendation(
                      uriId = item.uriId,
                      userId = userId,
                      masterScore = computeMasterScore(item.uriScores),
                      allScores = item.uriScores,
                      seen = false,
                      clicked = false,
                      kept = false,
                      attribution = item.attribution
                    ))
                  }
                }

                genStateRepo.save(newState)
              }

              precomputeRecommendationsForUser(userId)

              !seedItems.isEmpty
            }
          }
      }
      res.onFailure {
        case t: Throwable => airbrake.notify("Failure during recommendation precomputation", t)
      }
      res
    }
  }

  def precomputeRecommendations(): Unit = {
    usersToPrecomputeRecommendationsFor().map { userIds =>
      if (recommendationGenerationLock.waiting < userIds.length + 1) {
        userIds.foreach(precomputeRecommendationsForUser)
      }
    }
  }

}
