package com.keepit.curator.commanders

import com.keepit.curator.model.Recommendation
import com.keepit.common.db.Id
import com.keepit.model.User

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationGenerationCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    scoringHelper: UriScoringHelper) {

  def getAdHocRecommendations(userId: Id[User], howManyMax: Int): Future[Seq[Recommendation]] = {
    val seedsFuture = seedCommander.getTopItems(userId, Math.max(howManyMax, 250))
    val scoredItemsFuture = seedsFuture.flatMap { seeds => scoringHelper(seeds) }
    scoredItemsFuture.map { scoredItems =>
      scoredItems.map { scoredItem =>
        Recommendation(
          userId = scoredItem.userId,
          uriId = scoredItem.uriId,
          score = 0.5f * scoredItem.uriScores.recencyScore + 2 * scoredItem.uriScores.overallInterestScore + 0.25f * scoredItem.uriScores.priorScore, //this math is just for testing
          explain = Some(scoredItem.uriScores.toString)
        )
      }.sortBy(-1 * _.score).take(howManyMax)
    }

  }

}
