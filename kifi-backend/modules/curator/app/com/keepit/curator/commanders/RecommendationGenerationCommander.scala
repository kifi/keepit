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
    val seedsFuture = seedCommander.getRecentItems(userId, howManyMax)
    val scoredItemsFuture = seedsFuture.flatMap { seeds => scoringHelper(seeds) }
    scoredItemsFuture.map { scoredItems =>
      scoredItems.map { scoredItem =>
        Recommendation(
          userId = scoredItem.userId,
          uriId = scoredItem.uriId,
          score = scoredItem.uriScores.recencyScore, //this is just for testing
          explain = Some(Json.stringify(Json.toJson(scoredItem.uriScores)))
        )
      }
    }

  }

}
