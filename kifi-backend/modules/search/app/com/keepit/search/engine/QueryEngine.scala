package com.keepit.search.engine

import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBuffer, HashJoin }
import org.apache.lucene.search.{ Query, Weight }

class QueryEngine private[engine] (scoreExpr: ScoreExpr, query: Query, totalSize: Int, coreSize: Int) {

  private[this] val dataBuffer: DataBuffer = new DataBuffer()
  private[this] val matchWeight: Array[Float] = new Array[Float](totalSize)

  private[this] def accumulateWeightInfo(weights: IndexedSeq[(Weight, Float)]): Unit = {
    var i = 0
    while (i < totalSize) {
      matchWeight(i) += weights(i)._2
      i += 1
    }
  }
  private def normalizeMatchWeight(): Unit = {
    var sum = 0.0f
    var i = 0
    while (i < totalSize) {
      sum += matchWeight(i)
      i += 1
    }
    if (sum != 0.0f) {
      i = 0
      while (i < totalSize) {
        matchWeight(i) += matchWeight(i) / sum
        i += 1
      }
    }
  }

  def execute(source: ScoreVectorSource): Unit = {
    // if NullExpr, no need to execute
    if (scoreExpr.isNullExpr) return

    val weights = source.createWeights(query)
    if (weights.nonEmpty) {
      // extract and accumulate information from Weights for later use (percent match)
      accumulateWeightInfo(weights)

      source.execute(weights, coreSize, dataBuffer)
    }
  }

  def createScoreContext(collector: ResultCollector[ScoreContext]): ScoreContext = {
    new ScoreContext(scoreExpr, totalSize, matchWeight, collector)
  }

  def join(collector: ResultCollector[ScoreContext]): Unit = {
    val size = dataBuffer.size
    if (size > 0) {
      normalizeMatchWeight()

      val hashJoin = new HashJoin(dataBuffer, (size + 10) / 10, createScoreContext(collector))
      hashJoin.execute()
    }
  }

  def getScoreExpr(): ScoreExpr = scoreExpr
  def getQuery(): Query = query
  def getTotalSize(): Int = totalSize
  def getCoreSize(): Int = coreSize
}
