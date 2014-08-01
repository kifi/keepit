package com.keepit.search.engine.result

import com.keepit.search.engine.{ Visibility, ScoreContext, MaxExpr, DisjunctiveSumExpr }
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.LongArraySet
import org.specs2.mutable.Specification

class MainResultCollectorTest extends Specification {
  private class TstResultClickBoosts(clickedIds: Set[Long] = Set.empty[Long], boost: Float = 1.0f) extends ResultClickBoosts {
    override def apply(id: Long): Float = if (clickedIds.contains(id)) boost else 1.0f
  }

  private val expr = DisjunctiveSumExpr(Seq(MaxExpr(0), MaxExpr(1), MaxExpr(2)))
  private val exprSize = 3

  "MainResultCollector" should {
    "collect hits above MIN_PERCENT_MATCH" in {
      val collector = new MainResultCollector(
        clickBoosts = new TstResultClickBoosts(),
        friendsUris = LongArraySet.empty,
        maxHitsPerCategory = 10,
        percentMatchThreshold = 0.0f)
      val ctx = new ScoreContext(expr, exprSize, 1.0f, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()

      ctx.set(20)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 0
      oHits.size === 0

      val hit = mHits.pop()
      hit.hit.id === 20
      hit.score === 2.0f * 0.7f
    }

    "collect hits above percentMatchThreshold" in {
      val collector = new MainResultCollector(
        clickBoosts = new TstResultClickBoosts(),
        friendsUris = LongArraySet.empty,
        maxHitsPerCategory = 10,
        percentMatchThreshold = 0.7f)
      val ctx = new ScoreContext(expr, exprSize, 1.0f, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(40)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 2
      fHits.size === 0
      oHits.size === 0

      Set(mHits.pop().hit.id, mHits.pop().hit.id) === Set(30L, 40L)
    }

    "collect a hit below percentMatchThreshold if clicked" in {
      val collector = new MainResultCollector(
        clickBoosts = new TstResultClickBoosts(Set(20L), 2.0f),
        friendsUris = LongArraySet.empty,
        maxHitsPerCategory = 10,
        percentMatchThreshold = 0.9f)
      val ctx = new ScoreContext(expr, exprSize, 1.0f, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 0
      oHits.size === 0

      val hit = mHits.pop()
      hit.hit.id === 20L
      hit.score === 2.0f * 0.7f * 2.0f
    }

    "collect hits by category" in {
      val collector = new MainResultCollector(
        clickBoosts = new TstResultClickBoosts(Set(20L), 2.0f),
        friendsUris = LongArraySet.from(Array(20L)),
        maxHitsPerCategory = 10,
        percentMatchThreshold = 0.0f)
      val ctx = new ScoreContext(expr, exprSize, 1.0f, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.PUBLIC
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.PUBLIC
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.MEMBER
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 1
      oHits.size === 1

      mHits.pop().hit.id === 30L
      fHits.pop().hit.id === 20L
      oHits.pop().hit.id === 10L
    }

    "not collect restricted hits" in {
      val collector = new MainResultCollector(
        clickBoosts = new TstResultClickBoosts(Set(20L), 2.0f),
        friendsUris = LongArraySet.from(Array(20L)),
        maxHitsPerCategory = 10,
        percentMatchThreshold = 0.0f)
      val ctx = new ScoreContext(expr, exprSize, 1.0f, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()
      ctx.set(20)
      ctx.addScore(0, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()
      ctx.set(30)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.visibility = Visibility.RESTRICTED
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 0
      fHits.size === 0
      oHits.size === 0
    }
  }
}
