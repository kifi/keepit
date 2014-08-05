package com.keepit.model

import com.keepit.common.util.EnumFormatUtil
import play.api.libs.json.{ Writes, Reads }

object UriRecommendationFeedback extends Enumeration {
  type UriRecommendationFeedback = Value
  val seen = Value("seen")
  val clicked = Value("clicked")
  val kept = Value("kept")

  implicit val reads: Reads[UriRecommendationFeedback.Value] = EnumFormatUtil.enumReads(UriRecommendationFeedback)

  implicit def writes: Writes[UriRecommendationFeedback.Value] = EnumFormatUtil.enumWrites
}
