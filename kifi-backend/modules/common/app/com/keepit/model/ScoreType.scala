package com.keepit.model

import com.keepit.common.util.EnumFormatUtil
import play.api.libs.json._

object ScoreType extends Enumeration {
  type ScoreType = Value
  val socialScore = Value("socialScore")
  val popularityScore = Value("popularityScore")
  val overallInterestScore = Value("overallInterestScore")
  val recentInterestScore = Value("recentInterestScore")
  val recencyScore = Value("recencyScore")
  val priorScore = Value("priorScore")

  implicit val reads: Reads[ScoreType] = EnumFormatUtil.enumReads(ScoreType)

  implicit def writes: Writes[ScoreType] = EnumFormatUtil.enumWrites
}
