package com.keepit.curator.commanders

import com.keepit.curator.model.{ Recommendation, UserRecommendationGenerationStateRepo, UserRecommendationGenerationState, Keepers, ScoredSeedItem }
import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ ScoreType, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.Database

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    scoringHelper: UriScoringHelper,
    db: Database,
    genStateRepo: UserRecommendationGenerationStateRepo) {

  val defaultScore = 0.0f

  val recommendationGenerationLock = new ReactiveLock(3)

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

  private def computeMasterScore(scoredItem: ScoredSeedItem): Float = {
    0.3f * scoredItem.uriScores.socialScore + 2 * scoredItem.uriScores.overallInterestScore + 0.5f * scoredItem.uriScores.priorScore
  }

  def getAdHocRecommendations(userId: Id[User], howManyMax: Int, scoreCoefficients: Map[ScoreType.Value, Float]): Future[Seq[Recommendation]] = {
    val seedsFuture = for {
      seeds <- seedCommander.getTopItems(userId, Math.max(howManyMax, 200))
      restrictions <- shoebox.getAdultRestrictionOfURIs(seeds.map { _.uriId })
    } yield {
      (seeds zip restrictions) filterNot (_._2) map (_._1)
    }

    val scoredItemsFuture = seedsFuture.flatMap { seeds => scoringHelper(seeds) }
    scoredItemsFuture.map { scoredItems =>
      scoredItems.map { scoredItem =>
        Recommendation(
          userId = scoredItem.userId,
          uriId = scoredItem.uriId,
          score = if (scoreCoefficients.isEmpty)
            computeMasterScore(scoredItem)
          else
            scoreCoefficients.getOrElse(ScoreType.recencyScore, defaultScore) * scoredItem.uriScores.recencyScore
              + scoreCoefficients.getOrElse(ScoreType.overallInterestScore, defaultScore) * scoredItem.uriScores.overallInterestScore
              + scoreCoefficients.getOrElse(ScoreType.priorScore, defaultScore) * scoredItem.uriScores.priorScore
              + scoreCoefficients.getOrElse(ScoreType.socialScore, defaultScore) * scoredItem.uriScores.socialScore
              + scoreCoefficients.getOrElse(ScoreType.popularityScore, defaultScore) * scoredItem.uriScores.popularityScore
              + scoreCoefficients.getOrElse(ScoreType.recentInterestScore, defaultScore) * scoredItem.uriScores.recentInterestScore,
          explain = Some(scoredItem.uriScores.toString)
        )
      }.sortBy(-1 * _.score).take(howManyMax)
    }

  }

  private def precomputeRecommendationsForUser(userId: Id[User]): Unit = recommendationGenerationLock.withLockFuture {
    val state = db.readOnlyMaster { implicit session =>
      genStateRepo.getByUserId(userId)
    } getOrElse {
      UserRecommendationGenerationState(userId = userId)
    }
    val seedsFuture = for {
      seeds <- seedCommander.getBySeqNumAndUser(state.seq, userId, 200)
      restrictions <- shoebox.getAdultRestrictionOfURIs(seeds.map { _.uriId })
    } yield {
      (seeds zip restrictions) filterNot (_._2) map (_._1)
    }

    val res: Future[Boolean] = seedsFuture.flatMap { seedItems =>
      val newState = state.copy(seq = seedItems.map(_.seq).max)
      val cleanedItems = seedItems.filter { seedItem => //discard super popular items and the users own keeps
        seedItem.keepers match {
          case Keepers.ReasonableNumber(users) => !users.contains(userId)
          case _ => false
        }
      }
      scoringHelper(cleanedItems).map { scoredItems =>
        val toBeSavedItems = scoredItems.filter(computeMasterScore(_) > 0.5)
        db.readWrite { implicit session =>
          //actually saving the recommendation when Tans storage code is merged
          genStateRepo.save(newState)
        }
        if (!cleanedItems.isEmpty) {
          precomputeRecommendationsForUser(userId)
          true
        } else false
      }

    }
    res
  }

  def precomputeRecommendations(): Unit = {
    if (recommendationGenerationLock.waiting < FEED_PRECOMPUTATION_WHITELIST.length) {
      FEED_PRECOMPUTATION_WHITELIST.map(precomputeRecommendationsForUser)
    }
  }

}
