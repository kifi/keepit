package com.keepit.search.engine

import com.keepit.search.engine.result.ResultCollector

import scala.util.Random

import org.specs2.mutable.Specification

class ScoreExprTest extends Specification {
  private[this] val rnd = new Random()
  private[this] val size = 5

  private[this] val collector = new ResultCollector[ScoreContext] {
    private var _id = -1L
    private var _score = 0.0f

    def id: Long = _id
    def score: Float = _score
    def clear(): Unit = {
      _id = -1L
      _score = 0.0f
    }

    override def collect(ctx: ScoreContext): Unit = {
      val id = ctx.id
      val score = ctx.score

      if (score > 0.0f) {
        _id = id
        _score = score
      }
    }
  }

  private def mkCtx(expr: ScoreExpr, idx: Int*): ScoreContext = {
    val weights = new Array[Float](size)
    idx.foreach { i => weights(i) = 1.0f / idx.length.toFloat }
    new ScoreContext(expr, size, weights, collector)
  }

  "ScoreExpr" should {
    "compute scores correctly with NullExpr" in {
      val idx = rnd.nextInt(size)
      val ctx = mkCtx(NullExpr, idx)
      ctx.set(100L)
      for (i <- 0 until size) ctx.addScore(i, 1.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with Max" in {
      val idx = rnd.nextInt(size)
      val ctx = mkCtx(MaxExpr(idx), idx)
      ctx.set(200L)
      ctx.addScore(idx, 1.0f)
      ctx.addScore(idx, 2.0f)
      ctx.flush
      collector.id === 200L
      collector.score === 2.0f
    }

    "compute scores correctly with Sum" in {
      val idx = rnd.nextInt(size)
      val ctx = mkCtx(SumExpr(idx), idx)
      ctx.set(200L)
      ctx.addScore(idx, 1.0f)
      ctx.addScore(idx, 2.0f)
      ctx.flush
      collector.id === 200L
      collector.score === 3.0f
    }

    "compute scores correctly with MaxWithTieBreaker" in {
      val idx = rnd.nextInt(size)
      val ctx = mkCtx(MaxWithTieBreakerExpr(idx, 0.1f), idx)
      ctx.set(300L)
      ctx.addScore(idx, 2.0f)
      ctx.addScore(idx, 3.0f)
      ctx.flush
      collector.id === 300L
      collector.score === 3.2f
    }

    "compute scores correctly with DisjunctiveSumExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx.take(3).toArray
      val idx2 = allIdx.drop(3).toArray

      val ctx = mkCtx(DisjunctiveSumExpr(Seq(MaxExpr(idx1(0)), MaxExpr(idx1(1)), MaxExpr(idx1(2)))), idx1: _*)
      ctx.set(400L)
      ctx.addScore(idx1(0), 1.0f)
      ctx.addScore(idx1(1), 2.0f)
      ctx.addScore(idx1(2), 3.0f)
      ctx.flush
      collector.id === 400L
      collector.score === 6.0f

      ctx.set(401L)
      ctx.addScore(idx1(0), 1.0f)
      ctx.addScore(idx1(1), 2.0f)
      ctx.addScore(idx2(0), 3.0f)
      ctx.flush
      collector.id === 401L
      collector.score == 3.0f
    }

    "compute scores correctly with ConjunctiveSumExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx.take(3).toArray
      val idx2 = allIdx.drop(3).toArray

      val ctx = mkCtx(ConjunctiveSumExpr(Seq(MaxExpr(idx1(0)), MaxExpr(idx1(1)), MaxExpr(idx1(2)))), idx1: _*)
      ctx.set(500L)
      ctx.addScore(idx1(0), 1.0f)
      ctx.addScore(idx1(1), 2.0f)
      ctx.addScore(idx1(2), 3.0f)
      ctx.flush
      collector.id === 500L
      collector.score === 6.0f

      collector.clear()
      ctx.set(501L)
      ctx.addScore(idx1(0), 1.0f)
      ctx.addScore(idx1(1), 2.0f)
      ctx.addScore(idx2(0), 3.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with ExistsExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx.take(3).toArray
      val idx2 = allIdx.drop(3).toArray

      val ctx = mkCtx(ExistsExpr(Seq(MaxExpr(idx1(0)), MaxExpr(idx1(1)), MaxExpr(idx1(2)))), idx1: _*)
      ctx.set(600L)
      ctx.addScore(idx1(0), 2.0f)
      ctx.addScore(idx1(1), 3.0f)
      ctx.addScore(idx1(2), 4.0f)
      ctx.flush
      collector.id === 600L
      collector.score === 1.0f

      ctx.set(601L)
      ctx.addScore(idx1(0), 2.0f)
      ctx.addScore(idx1(1), 3.0f)
      ctx.flush
      collector.id === 601L
      collector.score === 1.0f

      ctx.set(602L)
      ctx.addScore(idx1(0), 2.0f)
      ctx.flush
      collector.id === 602L
      collector.score === 1.0f

      collector.clear()
      ctx.set(603L)
      ctx.addScore(idx2(0), 1.0f)
      ctx.addScore(idx2(1), 2.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with ForAllExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx.take(3).toArray
      val idx2 = allIdx.drop(3).toArray

      val ctx = mkCtx(ForAllExpr(Seq(MaxExpr(idx1(0)), MaxExpr(idx1(1)), MaxExpr(idx1(2)))), idx1: _*)
      ctx.set(700L)
      ctx.addScore(idx1(0), 2.0f)
      ctx.addScore(idx1(1), 3.0f)
      ctx.addScore(idx1(2), 4.0f)
      ctx.flush
      collector.id === 700L
      collector.score === 1.0f

      collector.clear()
      ctx.set(701L)
      ctx.addScore(idx1(0), 2.0f)
      ctx.addScore(idx1(1), 3.0f)
      ctx.addScore(idx2(0), 5.0f)
      ctx.addScore(idx2(1), 6.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with BooleanExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx(0)
      val idx2 = allIdx(1)
      val idx3 = allIdx(2)

      val ctx = mkCtx(BooleanExpr(optional = MaxExpr(idx1), required = MaxExpr(idx2)), idx1, idx2)
      ctx.set(800L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === 800L
      collector.score === 5.0f

      collector.clear()
      ctx.set(801L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f

      collector.clear()
      ctx.set(802L)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === 802L
      collector.score === 3.0f
    }

    "compute scores correctly with FilterExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx(0)
      val idx2 = allIdx(1)
      val idx3 = allIdx(2)

      val ctx = mkCtx(FilterExpr(expr = MaxExpr(idx1), filter = MaxExpr(idx2)), idx1)
      ctx.set(900L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === 900L
      collector.score === 2.0f

      collector.clear()
      ctx.set(901L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f

      collector.clear()
      ctx.set(902L)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with FilterOutExpr" in {
      val allIdx = rnd.shuffle((0 until size).toIndexedSeq)
      val idx1 = allIdx(0)
      val idx2 = allIdx(1)
      val idx3 = allIdx(2)

      val ctx = mkCtx(FilterOutExpr(expr = MaxExpr(idx1), filter = MaxExpr(idx2)), idx1)
      ctx.set(1000L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f

      collector.clear()
      ctx.set(1001L)
      ctx.addScore(idx1, 2.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === 1001L
      collector.score === 2.0f

      collector.clear()
      ctx.set(1002L)
      ctx.addScore(idx2, 3.0f)
      ctx.addScore(idx3, 4.0f)
      ctx.flush
      collector.id === -1L
      collector.score === 0.0f
    }

    "compute scores correctly with PercentMatchExpr" in {
      val numTerms = 16
      val threshold = 0.3f
      val allIdx = rnd.shuffle((0 until numTerms).toIndexedSeq)
      val idx = (0 until 8).map { n => allIdx.take(n) }.toArray

      val weights = new Array[Float](numTerms)
      allIdx.foreach { i => weights(i) = 1.0f / numTerms.toFloat }
      val ctx = new ScoreContext(PercentMatchExpr(DisjunctiveSumExpr(allIdx.map(MaxExpr(_))), threshold), numTerms, weights, collector)

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
          def factor(hits: Int, total: Int): Float = 1.0f - ((total - hits).toFloat / total.toFloat)

          val score = idx(n).map(i => (i + 1).toFloat).sum * factor(n, numTerms)

          collector.id === id
          (collector.score - score < 0.001f) === true
        }
        true
      } === true
    }
  }
}
