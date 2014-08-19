package com.keepit.search.engine.result

import com.keepit.search.ArticleHitQueue
import com.keepit.search.engine.ScoreContext
import com.keepit.search.engine.Visibility
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.LongArraySet

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
            myHits.insert(id, score, clickBoost, true)
          } else if ((visibility & Visibility.NETWORK) != 0) {
            friendsHits.insert(id, score, clickBoost, false)
          } else {
            othersHits.insert(id, score, clickBoost, false)
          }
        }
      }
    }
  }

  def getResults(): (ArticleHitQueue, ArticleHitQueue, ArticleHitQueue) = (myHits, friendsHits, othersHits)

  @inline private[this] def createQueue(sz: Int) = new ArticleHitQueue(sz)
}
