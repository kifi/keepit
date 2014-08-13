package com.keepit.curator.commanders

import com.keepit.curator.model.{ ScoredSeedItemWithAttribution, RecommendationInfo, UserRecommendationGenerationStateRepo, UserRecommendationGenerationState, Keepers, UriRecommendationRepo, UriRecommendation, UriScores }
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.collection.concurrent.TrieMap

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    scoringHelper: UriScoringHelper,
    boostingHelper: UriBoostingHelper,
    attributionHelper: SeedAttributionHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    uriRecRepo: UriRecommendationRepo,
    genStateRepo: UserRecommendationGenerationStateRepo) {

  val defaultScore = 0.0f

  val recommendationGenerationLock = new ReactiveLock(10)
  val perUserRecommendationGenerationLocks = TrieMap[Id[User], ReactiveLock]()

  val FEED_PRECOMPUTATION_WHITELIST: Seq[Id[User]] = Seq(
    1, //Eishay
    3, //Andrew
    7, //Yasu
    9, //Danny
    48, //Jared
    61, //Jen
    100, //Tamila
    115, //Yingjie
    134, //Léo
    243, //Stephen
    460, //Ray
    1114, //Martin
    2538, //Mark
    3466, //JP
    6498, //Tan
    6622, //David
    7100, //Aaron
    7456, //Josh
    7589, //Lydia
    8465, //Yiping
    8476 //Tommy
  ).map(Id[User](_)) //will go away once we release, just saving some computation/time for now

  private def computeMasterScore(scores: UriScores): Float = {
    5 * scores.socialScore +
      6 * scores.overallInterestScore +
      2 * scores.priorScore +
      1 * scores.recencyScore +
      1 * scores.popularityScore +
      9 * scores.recentInterestScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore
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
          {
            if (scoreCoefficients.isEmpty) {
              computeMasterScore(reco.allScores)
            } else {
              scoreCoefficients.recencyScore.getOrElse(defaultScore) * reco.allScores.recencyScore +
                scoreCoefficients.overallInterestScore.getOrElse(defaultScore) * reco.allScores.overallInterestScore +
                scoreCoefficients.priorScore.getOrElse(defaultScore) * reco.allScores.priorScore +
                scoreCoefficients.socialScore.getOrElse(defaultScore) * reco.allScores.socialScore +
                scoreCoefficients.popularityScore.getOrElse(defaultScore) * reco.allScores.popularityScore +
                scoreCoefficients.recentInterestScore.getOrElse(defaultScore) * reco.allScores.recentInterestScore +
                scoreCoefficients.rekeepScore.getOrElse(defaultScore) * reco.allScores.rekeepScore +
                scoreCoefficients.discoveryScore.getOrElse(defaultScore) * reco.allScores.discoveryScore
            }
          },
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
            Future.successful(false)
          } else {
            val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
              seedItem.keepers match {
                case Keepers.ReasonableNumber(users) => !users.contains(userId)
                case _ => false
              }
            }
            val boostedItems = boostingHelper(cleanedItems)
            val toBeSaved: Future[Seq[ScoredSeedItemWithAttribution]] = scoringHelper(boostedItems).map { scoredItems =>
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
                      masterScore = computeMasterScore(item.uriScores) * item.multiplier,
                      allScores = item.uriScores,
                      attribution = item.attribution
                    ))
                  } getOrElse {
                    uriRecRepo.save(UriRecommendation(
                      uriId = item.uriId,
                      userId = userId,
                      masterScore = computeMasterScore(item.uriScores) * item.multiplier,
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

              if (!cleanedItems.isEmpty) {
                precomputeRecommendationsForUser(userId)
                true
              } else false

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
    if (recommendationGenerationLock.waiting < FEED_PRECOMPUTATION_WHITELIST.length) {
      FEED_PRECOMPUTATION_WHITELIST.map(precomputeRecommendationsForUser)
    }
  }

}
