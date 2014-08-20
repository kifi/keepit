package com.keepit.search.engine.result

import com.keepit.search.engine.ScoreContext
import com.keepit.search.engine.Visibility
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.{ Hit, HitQueue }

object KifiResultCollector {
  val MIN_PERCENT_MATCH = 0.5f
}

class KifiResultCollector(clickBoosts: ResultClickBoosts, maxHitsPerCategory: Int, percentMatchThreshold: Float) extends ResultCollector[ScoreContext] {

  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id
    val visibility = ctx.visibility
    if (visibility != Visibility.RESTRICTED) {
      // compute he percent match value. set to 0.0f if less than the MIN_PERCENT_MATCH
      val percentMatch = ctx.computePercentMatch(KifiResultCollector.MIN_PERCENT_MATCH)

      if (percentMatch > 0.0f) {
        // compute clickBoost and score
        var clickBoost = 0.0f
        var score = 0.0f

        if (percentMatch >= percentMatchThreshold) {
          score = ctx.score() * percentMatch
          clickBoost = clickBoosts(id)
        } else {
          // below the threshold (and above MIN_PERCENT_MATCH), we save this hit if this is a clicked hit (clickBoost > 1.0f)
          clickBoost = clickBoosts(id)
          if (clickBoost > 1.0f) score = ctx.score() * percentMatch // else score remains 0.0f
        }

        if (score > 0.0f) {
          if ((visibility & Visibility.MEMBER) != 0) {
            myHits.insert(id, score, clickBoost, Visibility.MEMBER, ctx.visibleCount)
          } else if ((visibility & Visibility.NETWORK) != 0) {
            friendsHits.insert(id, score, clickBoost, Visibility.NETWORK, ctx.visibleCount)
          } else {
            othersHits.insert(id, score, clickBoost, Visibility.OTHERS, 0)
          }
        }
      }
    }
  }

  def getResults(): (KifiHitQueue, KifiHitQueue, KifiHitQueue) = (myHits, friendsHits, othersHits)

  @inline private[this] def createQueue(sz: Int) = new KifiHitQueue(sz)
}

class KifiHitQueue(sz: Int) extends HitQueue[MutableKifiHit](sz) {

  override def lessThan(a: Hit[MutableKifiHit], b: Hit[MutableKifiHit]) = (a.score < b.score || (a.score == b.score && a.hit.id < b.hit.id))

  def insert(id: Long, textScore: Float, clickBoost: Float, visibility: Int, visibleCount: Int) {
    if (overflow == null) {
      insert(textScore * clickBoost, null, new MutableKifiHit(id, textScore, clickBoost, visibility, visibleCount))
    } else {
      insert(textScore * clickBoost, null, overflow.hit(id, textScore, clickBoost, visibility, visibleCount))
    }
  }
}

// mutable hit object for efficiency
class MutableKifiHit(
    var id: Long,
    var luceneScore: Float,
    var clickBoost: Float,
    var visibility: Int,
    var visibleCount: Int) {
  def apply(
    newId: Long,
    newLuceneScore: Float,
    newClickBoost: Float,
    newVisibility: Int,
    newVisibleCount: Int): MutableKifiHit = {
    id = newId
    luceneScore = newLuceneScore
    clickBoost = newClickBoost
    visibility = newVisibility
    visibleCount = newVisibleCount
    this
  }
}

