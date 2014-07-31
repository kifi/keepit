package com.keepit.search.engine.result

import com.keepit.search.ArticleHitQueue
import com.keepit.search.engine.ScoreContext
import com.keepit.search.engine.Visibility
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.LongArraySet

object MainResultCollector {
  val MIN_PERCENT_MATCH = 0.5f
}

class MainResultCollector(clickBoosts: ResultClickBoosts, friendsUris: LongArraySet, maxHitsPerCategory: Int, percentMatchThreshold: Float) extends ResultCollector[ScoreContext] {

  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id
    val visibility = ctx.visibility
    if (visibility != Visibility.RESTRICTED) {
      val percentMatch = ctx.percentMatch(MainResultCollector.MIN_PERCENT_MATCH)

      if (percentMatch > 0.0f) {
        // compute clickBoost and score
        var clickBoost = 0.0f
        var score = 0.0f
        if (percentMatch < percentMatchThreshold) {
          clickBoost = clickBoosts(id)
          // if this is a clicked hit (clickBoost > 0.0f), ignore the threshold
          if (clickBoost > 0.0f) score = ctx.score() * percentMatch // else score remains 0.0f
        } else {
          score = ctx.score() * percentMatch
          // compute clickBoost only when score > 0.0f (percent match is above the threshold)
          if (score > 0.0f) clickBoost = clickBoosts(id)
        }

        if (score > 0.0f) {
          val clickBoost = clickBoosts(id)
          if ((visibility & Visibility.MEMBER) != 0) {
            myHits.insert(id, score, clickBoost, true, false)
          } else if (friendsUris.findIndex(id) >= 0) {
            friendsHits.insert(id, score, clickBoost, false, false)
          } else {
            othersHits.insert(id, score, clickBoost, false, false)
          }
        }
      }
    }
  }

  def getResults(): (ArticleHitQueue, ArticleHitQueue, ArticleHitQueue) = (myHits, friendsHits, othersHits)

  @inline private[this] def createQueue(sz: Int) = new ArticleHitQueue(sz)
}
