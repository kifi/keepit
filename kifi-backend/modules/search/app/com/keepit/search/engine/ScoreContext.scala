package com.keepit.search.engine

import java.util.Arrays

import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBuffer, DataBufferReader, Joiner }

class ScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    val norm: Float,
    val matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends Joiner {

  private[engine] var visibility: Int = 0 // 0: restricted, 1: public, 2: member
  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  def score(): Float = scoreExpr()(this)

  def percentMatch(threshold: Float): Float = {
    val len = scoreMax.length
    var pct = 1.0f
    var i = 0
    while (i < len) { // using while for performance
      if (scoreMax(i) <= 0.0f) {
        pct -= matchWeight(i)
        if (pct < threshold) return 0.0f
      }
      i += 1
    }
    pct
  }

  def clear(): Unit = {
    visibility = 0
    Arrays.fill(scoreMax, 0.0f)
    Arrays.fill(scoreSum, 0.0f)
  }

  def join(reader: DataBufferReader): Unit = {
    // compute the visibility
    visibility = visibility | reader.recordType

    while (reader.hasMore) {
      val bits = reader.nextTaggedFloatBits()
      val idx = DataBuffer.getTaggedFloatTag(bits)
      val scr = DataBuffer.getTaggedFloatValue(bits)
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
