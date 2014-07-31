package com.keepit.common.util

import com.keepit.model.ScoreType
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsValue, Json }
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat

class ScoreTypeMapFormatTest extends Specification {

  "RecommendationsController" should {

    "read and write json for ScoreType Map" in {

      val inputJson = Json.obj(
        "socialScore" -> 1.0,
        "overallInterestScore" -> 2.0,
        "priorScore" -> 3.0
      )

      val map = inputJson.as[Map[ScoreType.Value, Float]]

      map.get(ScoreType.socialScore) === 1f
      map.get(ScoreType.overallInterestScore).get === 2f
      map.get(ScoreType.priorScore).get === 3f

      val outputJson = Json.toJson(map)

      outputJson === inputJson

    }
  }
}
