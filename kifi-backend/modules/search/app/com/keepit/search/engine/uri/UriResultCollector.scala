package com.keepit.search.engine.uri

import com.keepit.common.logging.Logging
import com.keepit.search.engine.library.LibrarySearchExplanationBuilder
import com.keepit.search.engine.result.{ HitQueue, ResultCollector }
import com.keepit.search.engine.{ ScoreContext, Visibility }
import com.keepit.search.tracking.ResultClickBoosts

object UriResultCollector {
  val MIN_MATCHING = 0.6f

  def createQueue(sz: Int): HitQueue = new HitQueue(sz)
}

abstract class UriResultCollector extends ResultCollector[ScoreContext] {
  def getResults(): (HitQueue, HitQueue, HitQueue)
}

class UriResultCollectorWithBoost(clickBoostsProvider: () => ResultClickBoosts, maxHitsPerCategory: Int, matchingThreshold: Float, sharingBoost: Float, explanation: Option[UriSearchExplanationBuilder]) extends UriResultCollector with Logging {

  import com.keepit.search.engine.uri.UriResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)
  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  private[this] lazy val clickBoosts: ResultClickBoosts = clickBoostsProvider()

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id

    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(minMatchingThreshold)

    if (matching > 0.0f) {
      // compute clickBoost and score
      var score = 0.0f
      val clickBoost = clickBoosts(id)

      if (matching >= matchingThreshold) {
        score = ctx.score() * matching * clickBoost
      } else {
        // below the threshold (and above minMatchingThreshold), we save this hit if this is a clicked hit (clickBoost > 1.0f)
        if (clickBoost > 1.0f) score = ctx.score() * matching * clickBoost // else score remains 0.0f
      }

      if (score > 0.0f) {
        val visibility = ctx.visibility
        if ((visibility & Visibility.OWNER) != 0) {
          score = score * (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
          myHits.insert(id, score, visibility, ctx.secondaryId)
        } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
          score = score * (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
          friendsHits.insert(id, score, visibility, ctx.secondaryId)
        } else {
          othersHits.insert(id, score, visibility, ctx.secondaryId)
        }
        explanation.foreach { builder =>
          builder.collectRawScore(ctx, matchingThreshold, minMatchingThreshold)
          builder.collectBoostedScore(id, score, Some(clickBoost), Some(sharingBoost))
        }
      }
    }
  }

  def getResults(): (HitQueue, HitQueue, HitQueue) = (myHits, friendsHits, othersHits)
}

class UriResultCollectorWithNoBoost(maxHitsPerCategory: Int, matchingThreshold: Float, explanation: Option[UriSearchExplanationBuilder]) extends UriResultCollector with Logging {

  import com.keepit.search.engine.uri.UriResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)
  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id

    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(minMatchingThreshold)

    if (matching > 0.0f) {
      val score = ctx.score()
      if (score > 0.0f) {
        val visibility = ctx.visibility
        if ((visibility & Visibility.OWNER) != 0) {
          myHits.insert(id, score, visibility, ctx.secondaryId)
        } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
          friendsHits.insert(id, score, visibility, ctx.secondaryId)
        } else {
          othersHits.insert(id, score, visibility, ctx.secondaryId)
        }
        explanation.foreach { builder =>
          builder.collectRawScore(ctx, matchingThreshold, minMatchingThreshold)
          builder.collectBoostedScore(id, score, None, None)
        }
      }
    }
  }

  def getResults(): (HitQueue, HitQueue, HitQueue) = (myHits, friendsHits, othersHits)
}

class NonUserUriResultCollector(maxHitsPerCategory: Int, matchingThreshold: Float, explanation: Option[UriSearchExplanationBuilder]) extends ResultCollector[ScoreContext] with Logging {

  import com.keepit.search.engine.uri.UriResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)
  private[this] val hits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(minMatchingThreshold)

    if (matching > 0.0f) {
      val score = ctx.score() * matching
      if (score > 0.0f) {
        hits.insert(ctx.id, score, Visibility.OTHERS | (ctx.visibility & Visibility.HAS_SECONDARY_ID), ctx.secondaryId)
        explanation.foreach { builder =>
          builder.collectRawScore(ctx, matchingThreshold, minMatchingThreshold)
          builder.collectBoostedScore(ctx.id, score, None, None)
        }
      }

    }
  }

  def getResults(): HitQueue = hits
}
