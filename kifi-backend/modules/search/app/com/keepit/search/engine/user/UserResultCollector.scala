package com.keepit.search.engine.user

import com.keepit.common.logging.Logging
import com.keepit.model.LibraryKind
import com.keepit.search.engine.uri.UriResultCollector
import com.keepit.search.engine.{ LibraryQualityEvaluator, Visibility, ScoreContext }
import com.keepit.search.engine.result.{ HitQueue, ResultCollector }
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.library.LibraryIndexable

class UserResultCollector(librarySearcher: Searcher, keepSearcher: Searcher, maxHitsPerCategory: Int, myFriendBoost: Float, matchingThreshold: Float, libraryQualityEvaluator: LibraryQualityEvaluator, explanation: Option[UserSearchExplanationBuilder]) extends ResultCollector[ScoreContext] with Logging {

  import UriResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)
  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  override def collect(ctx: ScoreContext): Unit = {
    val id = ctx.id

    // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
    val matching = ctx.computeMatching(minMatchingThreshold)

    if (matching > 0.0f) {
      var score = 0.0f

      if (matching >= matchingThreshold) {
        score = ctx.score() * matching
      }

      if (score > 0.0f) {
        val visibility = ctx.visibility
        val relevantQueue = if ((visibility & (Visibility.OWNER | Visibility.MEMBER | Visibility.NETWORK)) != 0) {
          myHits
        } else {
          othersHits
        }

        // todo(Léo): user total follower count instead of best library member count (=> UserQualityEvaluator)
        val libId = ctx.secondaryId

        if (libId > 0) {
          val memberCount = LibraryIndexable.getMemberCount(librarySearcher, libId) getOrElse 1L
          val popularityBoost = libraryQualityEvaluator.getPopularityBoost(memberCount)
          score = score * popularityBoost
        }

        if ((visibility & (Visibility.OWNER | Visibility.MEMBER | Visibility.NETWORK)) != 0) { score = score * myFriendBoost }
        else { // todo(Léo): user total keep count instead of best library keep count (=> UserQualityEvaluator)
          if (libId > 0) {
            val keepCount = libraryQualityEvaluator.estimateKeepCount(keepSearcher, id)
            val publishedLibraryBoost = libraryQualityEvaluator.getPublishedLibraryBoost(keepCount)
            score = score * publishedLibraryBoost
          }
        }

        // todo(Léo): use friend in common to boost users (=> UserQualityEvaluator)

        relevantQueue.insert(id, score, visibility, ctx.secondaryId)
      }

      explanation.foreach { builder =>
        builder.collectRawScore(ctx, matchingThreshold, minMatchingThreshold)
        builder.collectScore(id, score, myFriendBoost)
      }
    }
  }

  def getResults(): (HitQueue, HitQueue) = (myHits, othersHits)
}
