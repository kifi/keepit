package com.keepit.search.engine

import java.util.Arrays

import com.keepit.search.util.join.{ DataBufferReader, Joiner }

class ScoreContext(
    scoreExpr: ScoreExpr,
    scoreArraySize: Int,
    val matchWeight: Array[Float],
    collector: ResultCollector) extends Joiner {

  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  def clear(): Unit = {
    Arrays.fill(scoreMax, 0.0f)
    Arrays.fill(scoreSum, 0.0f)
  }

  def join(reader: DataBufferReader): Unit = {
    while (reader.hasMore) {
      val idx = reader.getTaggedFloatTag()
      val scr = reader.nextTaggedFloatValue()
      if (scoreMax(idx) < scr) scoreMax(idx) = scr
      scoreSum(idx) += scr
    }
  }

  def flush(): Unit = {
    val score = scoreExpr()(this)
    if (score > 0.0f) {
      collector.collect(this.id, score)
    }
  }

  // for testing
  private[engine] def addScore(idx: Int, scr: Float) = {
    if (scoreMax(idx) < scr) scoreMax(idx) = scr
    scoreSum(idx) += scr
  }
}
