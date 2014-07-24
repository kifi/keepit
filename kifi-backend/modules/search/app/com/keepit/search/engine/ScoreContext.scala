package com.keepit.search.engine

import java.util.Arrays

import com.keepit.search.util.join.{ DataBufferReader, Joiner }

class ScoreContext(scoreExpr: ScoreExpression, scoreArraySize: Int, normalizedWeight: Array[Float], threshold: Float) extends Joiner {
  private[engine] val scoreMax = new Array[Float](scoreArraySize)
  private[engine] val scoreSum = new Array[Float](scoreArraySize)

  private[this] def matchFactor(): Float = {
    var pct = 1.0f
    var i = 0
    while (i < scoreMax.length) { // using while for performance
      if (scoreMax(i) <= 0.0f) {
        pct -= normalizedWeight(i)
        if (pct < threshold) return 0.0f
      }
      i += 1
    }
    pct
  }

  def clean(): Unit = {
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
    val factor = matchFactor
    if (matchFactor > 0.0f) {
      val score = scoreExpr()(this) * factor
      // TODO
    }
  }
}
