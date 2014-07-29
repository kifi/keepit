package com.keepit.search.engine

import java.util.Arrays

import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBufferReader, Joiner }

class ScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    val matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends Joiner {

  private[engine] var visibility: Int = 0 // 0: restricted, 1: public, 2: member
  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  def score(): Float = scoreExpr()(this)

  def clear(): Unit = {
    visibility = 0
    Arrays.fill(scoreMax, 0.0f)
    Arrays.fill(scoreSum, 0.0f)
  }

  def join(reader: DataBufferReader): Unit = {
    // compute the visibility
    visibility = visibility | reader.recordType

    while (reader.hasMore) {
      val idx = reader.getTaggedFloatTag()
      val scr = reader.nextTaggedFloatValue()
      if (scoreMax(idx) < scr) scoreMax(idx) = scr
      scoreSum(idx) += scr
    }
  }

  def flush(): Unit = collector.collect(this)

  // for testing
  private[engine] def addScore(idx: Int, scr: Float) = {
    if (scoreMax(idx) < scr) scoreMax(idx) = scr
    scoreSum(idx) += scr
  }
}
