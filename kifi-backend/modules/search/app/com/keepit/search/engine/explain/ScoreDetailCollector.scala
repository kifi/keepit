package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.tracker.ResultClickBoosts
import scala.collection.mutable.ListBuffer

class ScoreDetailCollector(targetId: Long, clickBoostsProvider: Option[() => ResultClickBoosts], sharingBoost: Option[Float]) extends ResultCollector[ScoreContext] {

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

    clickBoostsProvider.foreach { f =>
      val clickBoosts = f()
      _clickBoostValue = clickBoosts(targetId)
    }

    sharingBoost.map { sharingBoost =>
      _sharingBoostValue = (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
    }

    _rawScore = ctx.score()

    _scoreComputation = ctx.explainScoreExpr()

    _details("aggregate") += ScoreDetail(ctx)
  }

  def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    require(primaryId == targetId, "id mismatch")

    _details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray.clone)
  }

  def rawScore(): Float = _rawScore
  def scoreComputation(): String = _scoreComputation
  def getDetails(): Map[String, Seq[ScoreDetail]] = _details.mapValues(_.toSeq)
  def getBoostValues(): (Float, Float) = (_clickBoostValue, _sharingBoostValue)
}
