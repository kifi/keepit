package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.engine.result.{ KifiResultCollector, ResultCollector }
import com.keepit.search.tracker.ResultClickBoosts
import scala.collection.mutable.ListBuffer

class ScoreDetailCollector(targetId: Long, clickBoostsProvider: Option[() => ResultClickBoosts], val matchingThreshold: Float, sharingBoost: Option[Float]) extends ResultCollector[ScoreContext] {

  val minMatchingThreshold = scala.math.min(matchingThreshold, KifiResultCollector.MIN_MATCHING)

  private[this] var _matching: Float = 0.0f
  private[this] var _boostedScore: Float = 0.0f
  private[this] var _rawScore: Float = 0.0f
  private[this] var _clickBoostValue: Float = -1f
  private[this] var _sharingBoostValue: Float = -1f
  private[this] var _scoreComputation: String = null

  private[this] val _details: Map[String, ListBuffer[ScoreDetail]] = Map(
    "aggregate" -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OWNER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.MEMBER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.NETWORK) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OTHERS) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.RESTRICTED) -> new ListBuffer[ScoreDetail]()
  )

  def collect(ctx: ScoreContext): Unit = {
    require(ctx.id == targetId, "id mismatch")

    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    _matching = ctx.computeMatching(minMatchingThreshold)
    _rawScore = ctx.score()

    clickBoostsProvider.foreach { f =>
      val clickBoosts = f()
      _clickBoostValue = clickBoosts(targetId)
    }

    if (_matching > 0.0f) {
      if (_matching >= matchingThreshold) {
        _boostedScore = _rawScore * _matching * _clickBoostValue
      } else {
        // below the threshold (and above minMatchingThreshold), we save this hit if this is a clicked hit (clickBoost > 1.0f)
        if (_clickBoostValue > 1.0f) _boostedScore = ctx.score() * _matching * _clickBoostValue // else score remains 0.0f
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

    _scoreComputation = ctx.explainScoreExpr()

    _details("aggregate") += ScoreDetail(ctx)
  }

  def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    require(primaryId == targetId, "id mismatch")

    _details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray.clone)
  }

  def getMatchingValues(): (Float, Float, Float) = (_matching, matchingThreshold, minMatchingThreshold)
  def getBoostValues(): (Float, Float) = (_clickBoostValue, _sharingBoostValue)
  def rawScore(): Float = _rawScore
  def boostedScore(): Float = _boostedScore
  def scoreComputation(): String = _scoreComputation
  def getDetails(): Map[String, Seq[ScoreDetail]] = _details.mapValues(_.toSeq)
}
