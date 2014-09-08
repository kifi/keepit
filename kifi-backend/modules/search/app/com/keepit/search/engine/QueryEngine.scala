package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBuffer, HashJoin }
import org.apache.lucene.search.{ Query, Weight }

class QueryEngine private[engine] (scoreExpr: ScoreExpr, query: Query, totalSize: Int, coreSize: Int) extends Logging {

  private[this] val dataBuffer: DataBuffer = new DataBuffer()
  private[this] val matchWeights: Array[Float] = new Array[Float](totalSize)

  private[this] def accumulateWeightInfo(weights: IndexedSeq[(Weight, Float)]): Unit = {
    var i = 0
    while (i < totalSize) {
      matchWeights(i) += weights(i)._2
      i += 1
    }
  }
  private def normalizeMatchWeight(): Unit = {
    var sum = 0.0f
    var i = 0
    while (i < totalSize) {
      sum += matchWeights(i)
      i += 1
    }
    if (sum != 0.0f) {
      i = 0
      while (i < totalSize) {
        matchWeights(i) = matchWeights(i) / sum
        i += 1
      }
    }
  }

  def execute(source: ScoreVectorSource): Int = {
    // if NullExpr, no need to execute
    if (scoreExpr.isNullExpr) return dataBuffer.size

    val weights = source.createWeights(query)
    if (weights.nonEmpty) {
      // extract and accumulate information from Weights for later use (percent match)
      accumulateWeightInfo(weights)

      source.execute(weights, coreSize, dataBuffer)
    } else {
      log.error("no weight created")
    }
    dataBuffer.size
  }

  def createScoreContext(collector: ResultCollector[ScoreContext]): ScoreContext = {
    new ScoreContext(scoreExpr, totalSize, matchWeights, collector)
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
  def getMatchWeights(): Array[Float] = matchWeights
}
