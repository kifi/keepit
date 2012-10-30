package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._
import play.api.libs.json._
import com.keepit.search.Scoring

class ScoringSerializer extends Writes[Scoring] with Logging {
  def writes(res: Scoring): JsValue =
    try {
      JsObject(List(
        "textScore" -> JsString(res.textScore.toString()),
        "normalizedTextScore" -> JsString(res.normalizedTextScore.toString()),
        "bookmarkScore" -> JsString(res.bookmarkScore.toString()),
        "boostedTextScore" -> JsString(res.boostedTextScore.toString()),
        "boostedBookmarkScore" -> JsString(res.boostedBookmarkScore.toString())
      ))
    } catch {
      case e =>
        log.error("can't serialize %s".format(res))
        throw e
    }
}

object ScoringSerializer {
  implicit val scoringSerializer = new ScoringSerializer
}
