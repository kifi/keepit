package com.keepit.common.util

import com.keepit.model.{ UriRecommendationScores }
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json }

class UriRecommendationScoresTest extends Specification {

  "RecommendationsController" should {

    "read and write json for ScoreType Map" in {

      val inputJson = Json.obj(
        "socialScore" -> 1.0,
        "overallInterestScore" -> 2.0
      )

      val scores = inputJson.as[UriRecommendationScores]

      scores.socialScore.get === 1f
      scores.overallInterestScore.get === 2f
      scores.priorScore === None

      val outputJson = Json.toJson(scores)

      outputJson === inputJson

    }
  }
}
