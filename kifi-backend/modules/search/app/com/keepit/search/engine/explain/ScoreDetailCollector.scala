package com.keepit.search.engine.explain

import com.keepit.search.engine.uri.UriResultCollector
import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.engine.result.ResultCollector
import scala.collection.mutable.ListBuffer

class ScoreDetailCollector(targetId: Long, val matchingThreshold: Float) extends ResultCollector[ScoreContext] {

  val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)

  private[this] var _matching: Float = 0.0f
  private[this] var _rawScore: Float = 0.0f
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
    if (ctx.id == targetId) {
      // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
      _matching = ctx.computeMatching(minMatchingThreshold)
      _rawScore = ctx.score()
      _scoreComputation = ctx.explainScoreExpr()
      _details("aggregate") += ScoreDetail(ctx)
    }
  }

  override def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    if (primaryId == targetId) {
      _details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray.clone)
    }
  }

  def getMatchingValues(): (Float, Float, Float) = (_matching, matchingThreshold, minMatchingThreshold)
  def getRawScore(): Float = _rawScore
  def getScoreComputation(): String = _scoreComputation
  def getDetails(): Map[String, Seq[ScoreDetail]] = _details.mapValues(_.toSeq)
}

