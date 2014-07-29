package com.keepit.search.engine.result

import com.keepit.search.ArticleHitQueue
import com.keepit.search.engine.ScoreContext
import com.keepit.search.engine.Visibility
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.util.LongArraySet

class MainResultCollector(clickBoosts: ResultClickBoosts, friendsUris: LongArraySet, maxHitsPerCategory: Int) extends ResultCollector[ScoreContext] {

  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id
    val visibility = ctx.visibility
    if (visibility != Visibility.RESTRICTED) {
      val score = ctx.score()
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

  def getResults(): (ArticleHitQueue, ArticleHitQueue, ArticleHitQueue) = (myHits, friendsHits, othersHits)

  @inline private[this] def createQueue(sz: Int) = new ArticleHitQueue(sz)
}
