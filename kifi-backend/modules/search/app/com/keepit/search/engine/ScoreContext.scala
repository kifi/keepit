package com.keepit.search.engine

import java.util.Arrays

import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBuffer, DataBufferReader, Joiner }

class ScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    val matchWeight: Array[Float],
    collector: ResultCollector[ScoreContext]) extends Joiner {

  private[engine] var visibility: Int = 0
  private[engine] var secondaryId: Long = -1 // secondary id (library id for kifi search)
  private[this] var secondaryId2Score: Float = -1.0f

  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  def score(): Float = scoreExpr()(this)

  def computePercentMatch(minThreshold: Float): Float = {
    val len = scoreMax.length
    var pct = 1.0f
    var i = 0
    while (i < len) { // using while for performance
      if (scoreMax(i) <= 0.0f) {
        pct -= matchWeight(i)
        if (pct < minThreshold) return 0.0f
      }
      i += 1
    }
    pct
  }

  def clear(): Unit = {
    visibility = 0
    secondaryId = -1L
    secondaryId2Score = -1.0f
    Arrays.fill(scoreMax, 0.0f)
    Arrays.fill(scoreSum, 0.0f)
  }

  def join(reader: DataBufferReader): Unit = {
    // compute the visibility
    val thisVisibility = reader.recordType
    visibility = visibility | thisVisibility

    if ((thisVisibility & Visibility.SEARCHABLE_KEEP) != 0) {
      // a searchable keep has the secondary id
      val id2 = reader.nextLong()
      var scr2 = 0.0f // use a simple sum of scores to compare secondary ids

      while (reader.hasMore) {
        val bits = reader.nextTaggedFloatBits()
        val idx = DataBuffer.getTaggedFloatTag(bits)
        val scr = DataBuffer.getTaggedFloatValue(bits)
        scr2 += scr
        if (scoreMax(idx) < scr) scoreMax(idx) = scr
        scoreSum(idx) += scr
      }

      if (scr2 > secondaryId2Score) {
        secondaryId = id2
        secondaryId2Score = scr2
      }
    } else {
      while (reader.hasMore) {
        val bits = reader.nextTaggedFloatBits()
        val idx = DataBuffer.getTaggedFloatTag(bits)
        val scr = DataBuffer.getTaggedFloatValue(bits)
        if (scoreMax(idx) < scr) scoreMax(idx) = scr
        scoreSum(idx) += scr
      }
    }
  }

  def flush(): Unit = collector.collect(this)

  // for testing
  private[engine] def addScore(idx: Int, scr: Float) = {
    if (scoreMax(idx) < scr) scoreMax(idx) = scr
    scoreSum(idx) += scr
  }
}
