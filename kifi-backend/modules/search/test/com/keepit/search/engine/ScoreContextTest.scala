package com.keepit.search.engine

import org.specs2.mutable.Specification

import scala.util.Random

class ScoreContextTest extends Specification {
  private[this] val rnd = new Random()

  private[this] val collector = new ResultCollector {
    private var _id = -1L
    private var _score = 0.0f

    def id: Long = _id
    def score: Float = _score
    def clear(): Unit = {
      _id = -1L
      _score = 0.0f
    }

    override def collect(id: Long, score: Float): Unit = {
      _id = id
      _score = score
    }
  }

  private def factor(hits: Int, total: Int): Float = {
    1.0f - ((total - hits).toFloat / total.toFloat)
  }

  "ScoreContext" should {
    "filter by threshold" in {
      val numTerms = 16
      val threshold = 0.3f
      val allIdx = rnd.shuffle((0 until numTerms).toIndexedSeq)
      val idx = (0 until 8).map { n => allIdx.take(n) }.toArray

      val weights = new Array[Float](numTerms)
      allIdx.foreach { i => weights(i) = 1.0f / numTerms.toFloat }
      val ctx = new ScoreContext(DisjunctiveSumExpr(allIdx.map(MaxExpr(_))), numTerms, weights, threshold, collector)

      (0 until 8).forall { n =>
        collector.clear()
        val id = 1100L + n
        ctx.set(id)
        idx(n).foreach { i => ctx.addScore(i, (i + 1).toFloat) }
        ctx.flush

        if ((n.toFloat / numTerms.toFloat) < threshold) {
          collector.id === -1
          collector.score === 0.0f
        } else {
          val score = idx(n).map(i => (i + 1).toFloat).sum * factor(n, numTerms)

          collector.id === id
          (collector.score - score < 0.001f) === true
        }
        true
      } === true
    }
  }
}
