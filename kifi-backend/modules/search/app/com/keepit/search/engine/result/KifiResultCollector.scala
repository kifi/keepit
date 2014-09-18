package com.keepit.search.engine.result

import com.keepit.common.logging.Logging
import com.keepit.search.engine.ScoreContext
import com.keepit.search.engine.Visibility
import com.keepit.search.tracker.ResultClickBoosts
import org.apache.lucene.util.PriorityQueue

object KifiResultCollector {
  val MIN_MATCHING = 0.5f

  class Hit(var id: Long, var score: Float, var normalizedScore: Float, var visibility: Int, var keepId: Long)

  class HitQueue(sz: Int) extends PriorityQueue[Hit](sz) {

    var highScore = Float.MinValue
    var totalHits = 0

    override def lessThan(a: Hit, b: Hit) = (a.normalizedScore < b.normalizedScore)

    override def insertWithOverflow(hit: Hit): Hit = {
      totalHits += 1
      if (hit.score > highScore) highScore = hit.score
      super.insertWithOverflow(hit)
    }

    private[this] var overflow: Hit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

    def insert(id: Long, score: Float, normalizedScore: Float, visibility: Int, keepId: Long) {
      if (overflow == null) {
        overflow = new Hit(id, score, normalizedScore, visibility, keepId)
      } else {
        overflow.id = id
        overflow.score = score
        overflow.visibility = visibility
        overflow.keepId = keepId
        overflow
      }
      overflow = insertWithOverflow(overflow)
    }

    def insert(hit: Hit) {
      overflow = insertWithOverflow(hit)
    }

    // the following method is destructive. after the call ArticleHitQueue is unusable
    def toSortedList: List[Hit] = {
      var res: List[Hit] = Nil
      var i = size()
      while (i > 0) {
        i -= 1
        res = pop() :: res
      }
      res
    }

    // the following method is destructive. after the call ArticleHitQueue is unusable
    def toRankedIterator = toSortedList.iterator.zipWithIndex

    def foreach(f: Hit => Unit) {
      val arr = getHeapArray()
      val sz = size()
      var i = 1
      while (i <= sz) {
        f(arr(i).asInstanceOf[Hit])
        i += 1
      }
    }

    def discharge(n: Int): List[Hit] = {
      var i = 0
      var discharged: List[Hit] = Nil
      while (i < n && size > 0) {
        discharged = pop() :: discharged
        i += 1
      }
      discharged
    }

    def reset() {
      super.clear()
      highScore = Float.MinValue
      totalHits = 0
    }
  }

  def createQueue(sz: Int) = new HitQueue(sz)
}

class KifiResultCollector(clickBoosts: ResultClickBoosts, maxHitsPerCategory: Int, matchingThreshold: Float) extends ResultCollector[ScoreContext] with Logging {

  import KifiResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id

    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(KifiResultCollector.MIN_MATCHING)

    if (matching > 0.0f) {
      // compute clickBoost and score
      var score = 0.0f
      val clickBoost = clickBoosts(id)

      if (matching >= matchingThreshold) {
        score = ctx.score() * matching * clickBoost
      } else {
        // below the threshold (and above MIN_MATCHING), we save this hit if this is a clicked hit (clickBoost > 1.0f)
        if (clickBoost > 1.0f) score = ctx.score() * matching * clickBoost // else score remains 0.0f
      }

      if (score > 0.0f) {
        val visibility = ctx.visibility
        if ((visibility & Visibility.OWNER) != 0) {
          myHits.insert(id, score, score, visibility, ctx.secondaryId)
        } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
          friendsHits.insert(id, score, score, visibility, ctx.secondaryId)
        } else {
          othersHits.insert(id, score, score, visibility, ctx.secondaryId)
        }
      }
    }
  }

  def getResults(): (HitQueue, HitQueue, HitQueue) = (myHits, friendsHits, othersHits)
}

class KifiNonUserResultCollector(maxHitsPerCategory: Int, matchingThreshold: Float) extends ResultCollector[ScoreContext] with Logging {

  import KifiResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val hits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(KifiResultCollector.MIN_MATCHING)

    if (matching >= matchingThreshold) {
      val score = ctx.score() * matching
      if (score > 0.0f) {
        hits.insert(ctx.id, score, score, Visibility.OTHERS | (ctx.visibility & Visibility.HAS_SECONDARY_ID), ctx.secondaryId)
      }
    }
  }

  def getResults(): HitQueue = hits
}
