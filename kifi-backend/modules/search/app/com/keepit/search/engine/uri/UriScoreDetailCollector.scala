package com.keepit.search.engine.uri

import com.keepit.search.engine.explain.ScoreDetailCollector
import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.tracking.ResultClickBoosts

class UriScoreDetailCollector(targetId: Long, matchingThreshold: Float, clickBoostsProvider: Option[() => ResultClickBoosts], sharingBoost: Option[Float]) extends ScoreDetailCollector(targetId, matchingThreshold) {

  private[this] var _boostedScore: Float = 0.0f
  private[this] var _clickBoostValue: Float = -1f
  private[this] var _sharingBoostValue: Float = -1f

  override def collect(ctx: ScoreContext): Unit = {
    if (ctx.id == targetId) {
      super.collect(ctx)

      val (matching, _, _) = getMatchingValues()
      val rawScore = getRawScore()

      clickBoostsProvider.foreach { f =>
        val clickBoosts = f()
        _clickBoostValue = clickBoosts(targetId)
      }

      if (matching > 0.0f) {
        if (matching >= matchingThreshold) {
          _boostedScore = rawScore * matching * _clickBoostValue
        } else {
          // below the threshold (and above minMatchingThreshold), we save this hit if this is a clicked hit (clickBoost > 1.0f)
          if (_clickBoostValue > 1.0f) _boostedScore = ctx.score() * matching * _clickBoostValue // else score remains 0.0f
        }

        sharingBoost.map { sharingBoost =>
          val visibility = ctx.visibility
          if ((visibility & Visibility.OWNER) != 0) {
            _sharingBoostValue = (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
          } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
            _sharingBoostValue = (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
          } else {
            _sharingBoostValue = 1.0f
          }

          _boostedScore *= _sharingBoostValue
        }
      }
    }
  }

  def getBoostValues(): (Float, Float) = (_clickBoostValue, _sharingBoostValue)
  def getBoostedScore(): Float = _boostedScore
}
