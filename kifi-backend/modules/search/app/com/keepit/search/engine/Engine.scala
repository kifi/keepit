package com.keepit.search.engine

import com.keepit.search.Searcher
import com.keepit.search.engine.query.KWeight
import com.keepit.search.index.{ IdMapper, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, HashJoin }
import org.apache.lucene.search.{ Scorer, Query, Weight }
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class Engine private[engine] (scoreExpr: ScoreExpression, query: Query, scoreArraySize: Int, threshold: Float) {

  private[this] val dataBuffer: DataBuffer = new DataBuffer()

  private[this] val matchWeight: Array[Float] = new Array[Float](scoreArraySize)

  private def accumulateMatchWeight(weights: ArrayBuffer[(Weight, Float)]): Unit = {
    var i = 0
    while (i < scoreArraySize) {
      matchWeight(i) += weights(i)._2
      i += 1
    }
  }
  private def normalizeMatchWeight(): Unit = {
    var sum = 0.0f
    var i = 0
    while (i < scoreArraySize) {
      sum += matchWeight(i)
      i += 1
    }
    if (sum != 0.0f) {
      i = 0
      while (i < scoreArraySize) {
        matchWeight(i) += matchWeight(i) / sum
        i += 1
      }
    }
  }

  def execute(searcher: Searcher)(f: (Array[Scorer], IdMapper) => ScoreVectorSource) = {
    val weight = searcher.createWeight(query)
    if (weight != null) {
      val weights = new ArrayBuffer[(Weight, Float)]
      weight.asInstanceOf[KWeight].getWeights(weights)

      accumulateMatchWeight(weights)
      val scorers = new Array[Scorer](scoreArraySize)
      searcher.indexReader.getContext.leaves.foreach { subReaderContext =>
        val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
        var i = 0
        while (i < scorers.length) {
          scorers(i) = weights(i)._1.scorer(subReaderContext, true, false, subReader.getLiveDocs)
          i += 1
        }
        val source = f(scorers, subReader.getIdMapper)
        source.score(dataBuffer)
      }
    }
  }

  def createScoreContext(): ScoreContext = {
    new ScoreContext(scoreExpr, scoreArraySize, matchWeight, threshold) {
      def hit(id: Long, score: Float): Unit = {
        // TODO
      }
    }
  }

  def join(): Unit = {
    val size = dataBuffer.size
    if (size > 0) {
      normalizeMatchWeight()

      val hashJoin = new HashJoin(dataBuffer, (size + 10) / 10, createScoreContext())
      hashJoin.execute()
    }
  }
}
