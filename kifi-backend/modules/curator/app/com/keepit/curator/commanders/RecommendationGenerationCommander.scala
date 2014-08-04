package com.keepit.curator.commanders

import com.keepit.curator.model.{ RecommendationInfo }
import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ ScoreType, User }
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    shoebox: ShoeboxServiceClient,
    scoringHelper: UriScoringHelper) {

  val defaultScore = 0.0f

  def getAdHocAdminRecommendations(userId: Id[User], howManyMax: Int, scoreCoefficients: Map[ScoreType.Value, Float]): Future[Seq[RecommendationInfo]] = {
    val seedsFuture = for {
      seeds <- seedCommander.getTopItems(userId, Math.max(howManyMax, 200))
      restrictions <- shoebox.getAdultRestrictionOfURIs(seeds.map { _.uriId })
    } yield {
      (seeds zip restrictions) filterNot (_._2) map (_._1)
    }

    val scoredItemsFuture = seedsFuture.flatMap { seeds => scoringHelper(seeds) }
    scoredItemsFuture.map { scoredItems =>
      scoredItems.map { scoredItem =>
        RecommendationInfo(
          userId = scoredItem.userId,
          uriId = scoredItem.uriId,
          score = if (scoreCoefficients.isEmpty)
            0.3f * scoredItem.uriScores.socialScore + 2 * scoredItem.uriScores.overallInterestScore + 0.5f * scoredItem.uriScores.priorScore
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

}
