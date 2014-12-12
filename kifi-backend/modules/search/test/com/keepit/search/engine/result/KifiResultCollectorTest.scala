package com.keepit.search.engine.result

import com.keepit.search.engine.{ Visibility, ScoreContext, MaxExpr, DisjunctiveSumExpr }
import com.keepit.search.tracking.ResultClickBoosts
import org.specs2.mutable.Specification

class KifiResultCollectorTest extends Specification {
  private class TstResultClickBoosts(clickedIds: Set[Long] = Set.empty[Long], boost: Float = 1.0f) extends ResultClickBoosts {
    override def apply(id: Long): Float = if (clickedIds.contains(id)) boost else 1.0f
  }

  private val expr = DisjunctiveSumExpr(Seq(MaxExpr(0), MaxExpr(1), MaxExpr(2)))
  private val exprSize = 3

  "MainResultCollector" should {
    "collect hits above matchingThreshold" in {
      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.7f,
        sharingBoost = 0.0f)
      val ctx = new ScoreContext(expr, exprSize, Array(0.3f, 0.3f, 0.4f), collector)

      ctx.set(10)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(1, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(20)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(30)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(40)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.addScore(2, 1.0f)
      ctx.degree = 1
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 2
      fHits.size === 0
      oHits.size === 0

      Set(mHits.pop().id, mHits.pop().id) === Set(30L, 40L)
    }

    "collect hits above matchingThreshold which is below MIN_MATCHING" in {
      val matchingThreshold = 0.2f

      (matchingThreshold < KifiResultCollector.MIN_MATCHING) === true

      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(),
        maxHitsPerCategory = 10,
        matchingThreshold = matchingThreshold,
        sharingBoost = 0.0f)

      val ctx = new ScoreContext(expr, exprSize, Array(0.1f, 0.2f, 0.7f), collector)

      ctx.set(10)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()

      ctx.set(20)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.degree = 1
      ctx.flush()

      ctx.set(30)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(2, 1.0f)
      ctx.degree = 1
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 2
      fHits.size === 0
      oHits.size === 0

      Set(mHits.pop().id, mHits.pop().id) === Set(20L, 30L)
    }

    "collect a hit below matchingThreshold if clicked" in {
      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 3.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.99f,
        sharingBoost = 0.0f)
      val remainingWeight = 1.0f - KifiResultCollector.MIN_MATCHING - 0.01f
      val ctx = new ScoreContext(expr, exprSize, Array(KifiResultCollector.MIN_MATCHING - 0.01f, 0.02f, remainingWeight), collector)

      ctx.set(10)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(20)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(30)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.addScore(1, 1.0f)
      ctx.degree = 1
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 1
      fHits.size === 0
      oHits.size === 0

      val hit = mHits.pop()
      hit.id === 20L
      hit.score === 2.0f * (KifiResultCollector.MIN_MATCHING + 0.01f) * 3.0f // sum * pctMatch * click boost
    }

    "collect hits by category" in {
      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 2.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f,
        sharingBoost = 0.0f)
      val ctx = new ScoreContext(MaxExpr(0), 1, Array(1.0f), collector)

      ctx.set(1)
      ctx.visibility = Visibility.RESTRICTED
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(10)
      ctx.visibility = Visibility.OTHERS
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(20)
      ctx.visibility = Visibility.NETWORK
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(30)
      ctx.visibility = Visibility.MEMBER
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(40)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
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
      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(Set(20L), 2.0f),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f,
        sharingBoost = 0.0f)
      val ctx = new ScoreContext(MaxExpr(0), 1, Array(1.0f), collector)

      ctx.set(10)
      ctx.visibility = Visibility.RESTRICTED
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(20)
      ctx.visibility = Visibility.RESTRICTED
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(30)
      ctx.visibility = Visibility.RESTRICTED
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 0
      fHits.size === 0
      oHits.size === 0
    }

    "boost scores by sharing degree" in {
      val collector = new KifiResultCollectorWithBoost(
        clickBoostsProvider = () => new TstResultClickBoosts(),
        maxHitsPerCategory = 10,
        matchingThreshold = 0.0f,
        sharingBoost = 1.0f)
      val ctx = new ScoreContext(expr, exprSize, Array(0.8f, 0.1f, 0.1f), collector)

      ctx.set(10)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 1
      ctx.flush()
      ctx.set(20)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 4
      ctx.flush()
      ctx.set(30)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 3
      ctx.flush()
      ctx.set(40)
      ctx.visibility = Visibility.OWNER
      ctx.addScore(0, 1.0f)
      ctx.degree = 2
      ctx.flush()

      val (mHits, fHits, oHits) = collector.getResults()
      mHits.size === 4
      fHits.size === 0
      oHits.size === 0

      mHits.toSortedList.map(_.id) === List(20, 30, 40, 10)
    }
  }
}
