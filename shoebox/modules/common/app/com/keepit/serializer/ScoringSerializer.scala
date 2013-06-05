package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._
import play.api.libs.json._
import com.keepit.search.Scoring
import scala.math._

class ScoringSerializer extends Format[Scoring] with Logging {
  def writes(res: Scoring): JsValue =
    try {
      JsObject(List(
        "textScore" -> JsNumber(res.textScore),
        "normalizedTextScore" -> JsNumber(res.normalizedTextScore),
        "bookmarkScore" -> JsNumber(res.bookmarkScore),
        "recencyScore" -> JsNumber(res.recencyScore),
        "boostedTextScore" -> (if (res.boostedTextScore.isNaN()) JsNull else JsNumber(res.boostedTextScore)),
        "boostedBookmarkScore" -> (if (res.boostedBookmarkScore.isNaN()) JsNull else JsNumber(res.boostedBookmarkScore)),
        "boostedRecencyScore" -> (if (res.boostedRecencyScore.isNaN()) JsNull else JsNumber(res.boostedRecencyScore))
      ))
    } catch {
      case e: Throwable =>
        log.error("can't serialize %s".format(res))
        throw e
    }

  def reads(json: JsValue): JsResult[Scoring] = JsSuccess({
    val score = new Scoring(
      textScore  = (json \ "textScore").as[Float],
      normalizedTextScore  = (json \ "normalizedTextScore").as[Float],
      bookmarkScore  = (json \ "bookmarkScore").as[Float],
      recencyScore  = (json \ "recencyScore").as[Float]
    )
    score.boostedTextScore = (json \ "boostedTextScore").asOpt[Float].getOrElse(Float.NaN)
    score.boostedBookmarkScore  = (json \ "boostedBookmarkScore").asOpt[Float].getOrElse(Float.NaN)
    score.boostedRecencyScore  = (json \ "boostedRecencyScore").asOpt[Float].getOrElse(Float.NaN)
    score
  })
}

object ScoringSerializer {
  implicit val scoringSerializer = new ScoringSerializer
}
