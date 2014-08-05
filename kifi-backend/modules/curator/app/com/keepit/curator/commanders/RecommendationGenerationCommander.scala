package com.keepit.curator.commanders

import com.keepit.curator.model.{
  RecommendationInfo,
  UserRecommendationGenerationStateRepo,
  UserRecommendationGenerationState,
  Keepers,
  ScoredSeedItem,
  UriRecommendationRepo,
  UriRecommendation,
  UriScores
}
import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ ScoreType, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.collection.mutable

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    scoringHelper: UriScoringHelper,
    db: Database,
    airbrake: AirbrakeNotifier,
    genStateRepo: UserRecommendationGenerationStateRepo,
    recoRepo: UriRecommendationRepo) {

  val defaultScore = 0.0f

  val recommendationGenerationLock = new ReactiveLock(6)
  val perUserRecommendationGenerationLocks = mutable.Map[Id[User], ReactiveLock]()

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
    0.3f * scores.socialScore + 2 * scores.overallInterestScore + 0.5f * scores.priorScore
  }

  def getAdHocRecommendations(userId: Id[User], howManyMax: Int, scoreCoefficients: Map[ScoreType.Value, Float]): Future[Seq[RecommendationInfo]] = {
    val recosFuture = db.readOnlyReplicaAsync { implicit session =>
      recoRepo.getByTopMasterScore(userId, Math.max(howManyMax, 1000))
    }

    recosFuture.map { recos =>
      recos.map { reco =>
        RecommendationInfo(
          userId = reco.userId,
          uriId = reco.uriId,
          score = if (scoreCoefficients.isEmpty)
            0.35f * reco.allScores.socialScore + 2.0f * reco.allScores.overallInterestScore + 1.0f * reco.allScores.recentInterestScore
          else
            scoreCoefficients.getOrElse(ScoreType.recencyScore, defaultScore) * reco.allScores.recencyScore
              + scoreCoefficients.getOrElse(ScoreType.overallInterestScore, defaultScore) * reco.allScores.overallInterestScore
              + scoreCoefficients.getOrElse(ScoreType.priorScore, defaultScore) * reco.allScores.priorScore
              + scoreCoefficients.getOrElse(ScoreType.socialScore, defaultScore) * reco.allScores.socialScore
              + scoreCoefficients.getOrElse(ScoreType.popularityScore, defaultScore) * reco.allScores.popularityScore
              + scoreCoefficients.getOrElse(ScoreType.recentInterestScore, defaultScore) * reco.allScores.recentInterestScore,
          explain = Some(reco.allScores.toString)
        )
      }.sortBy(-1 * _.score).take(howManyMax)
    }

  }

  private def getPerUserGenerationLock(userId: Id[User]): ReactiveLock = perUserRecommendationGenerationLocks.synchronized {
    val lockOption = perUserRecommendationGenerationLocks.get(userId)
    lockOption.getOrElse {
      val lock = new ReactiveLock()
      perUserRecommendationGenerationLocks += (userId -> lock)
      lock
    }
  }

  private def precomputeRecommendationsForUser(userId: Id[User]): Unit = recommendationGenerationLock.withLockFuture {
    getPerUserGenerationLock(userId).withLockFuture {
      val state = db.readOnlyMaster { implicit session =>
        genStateRepo.getByUserId(userId)
      } getOrElse {
        UserRecommendationGenerationState(userId = userId)
      }
      val seedsFuture = for {
        seeds <- seedCommander.getBySeqNumAndUser(state.seq, userId, 300)
        restrictions <- shoebox.getAdultRestrictionOfURIs(seeds.map { _.uriId })
      } yield {
        (seeds zip restrictions) filterNot (_._2) map (_._1)
      }

      val res: Future[Boolean] = seedsFuture.flatMap { seedItems =>
        if (seedItems.isEmpty) {
          Future.successful(false)
        } else {
          val newState = state.copy(seq = seedItems.map(_.seq).max)
          val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
            seedItem.keepers match {
              case Keepers.ReasonableNumber(users) => !users.contains(userId)
              case _ => false
            }
          }
          scoringHelper(cleanedItems).map { scoredItems =>
            val toBeSavedItems = scoredItems.filter(si => computeMasterScore(si.uriScores) > 0.3)
            db.readWrite { implicit session =>
              toBeSavedItems.map { item =>
                val recoOpt = recoRepo.getByUriAndUserId(item.uriId, userId, None)
                recoOpt.map { reco =>
                  recoRepo.save(reco.copy(
                    masterScore = computeMasterScore(item.uriScores),
                    allScores = item.uriScores
                  ))
                } getOrElse {
                  recoRepo.save(UriRecommendation(
                    uriId = item.uriId,
                    userId = userId,
                    masterScore = computeMasterScore(item.uriScores),
                    allScores = item.uriScores,
                    seen = false,
                    clicked = false,
                    kept = false
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
