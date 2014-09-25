package com.keepit.search.engine.result

import com.keepit.search.engine.{ Visibility, ScoreContext, MaxExpr, DisjunctiveSumExpr }
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.LongArraySet
import org.specs2.mutable.Specification

class KifiResultCollectorTest extends Specification {
  private class TstResultClickBoosts(clickedIds: Set[Long] = Set.empty[Long], boost: Float = 1.0f) extends ResultClickBoosts {
    override def apply(id: Long): Float = if (clickedIds.contains(id)) boost else 1.0f
  }

  private val expr = DisjunctiveSumExpr(Seq(MaxExpr(0), MaxExpr(1), MaxExpr(2)))
  private val exprSize = 3

  "MainResultCollector" should {
    "collect hits above MIN_MATCHING" in {
      val collector = new KifiResultCollector(
        clickBoostsProvider = () => new TstResultClickBoosts(),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f)
      val ctx = new ScoreContext(expr, exprSize, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()

      ctx.set(20)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 0
      oHits.size === 0

      val hit = mHits.pop()
      hit.id === 20
      hit.score === 2.0f * 0.7f
    }

    "collect hits above matchingThreshold" in {
      val collector = new KifiResultCollector(
        clickBoostsProvider = () => new TstResultClickBoosts(),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.7f)
      val ctx = new ScoreContext(expr, exprSize, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()
      ctx.set(40)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 2
      fHits.size === 0
      oHits.size === 0

      Set(mHits.pop().id, mHits.pop().id) === Set(30L, 40L)
    }

    "collect a hit below percentMatchThreshold if clicked" in {
      val collector = new KifiResultCollector(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 3.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.9f)
      val ctx = new ScoreContext(expr, exprSize, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 0
      oHits.size === 0

      val hit = mHits.pop()
      hit.id === 20L
      hit.score === 2.0f * 0.7f * 3.0f // sum * pctMatch * click boost
    }

    "collect hits by category" in {
      val collector = new KifiResultCollector(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 2.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f)
      val ctx = new ScoreContext(MaxExpr(0), 1, Array(1.0f), collector)

      ctx.set(1)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()
      ctx.set(10)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.OTHERS
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.NETWORK
      ctx.flush()
      ctx.set(30)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(40)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.OWNER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 2
      oHits.size === 1

      mHits.pop().id === 40L
      fHits.toSortedList.map(_.id).toSet === Set(20L, 30L)
      oHits.pop().id === 10L
    }

    "not collect restricted hits" in {
      val collector = new KifiResultCollector(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 2.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f)
      val ctx = new ScoreContext(MaxExpr(0), 1, Array(1.0f), collector)

      ctx.set(10)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()
      ctx.set(30)
      ctx.addScore(0, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 0
      fHits.size === 0
      oHits.size === 0
    }
  }
}
