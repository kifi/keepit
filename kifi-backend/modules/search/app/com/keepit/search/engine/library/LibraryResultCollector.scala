package com.keepit.search.engine.library

import com.keepit.common.logging.Logging
import com.keepit.model.LibraryKind
import com.keepit.search.engine.uri.UriResultCollector
import com.keepit.search.engine.{ LibraryQualityEvaluator, Visibility, ScoreContext }
import com.keepit.search.engine.result.{ HitQueue, ResultCollector }
import com.keepit.search.index.IndexerVersionProviders.LibraryMembership
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.search.index.graph.library.membership.LibraryMembershipIndexable

class LibraryResultCollector(librarySearcher: Searcher, libraryMembershipSearcher: Searcher, keepSearcher: Searcher, maxHitsPerCategory: Int, myLibraryBoost: Float, matchingThreshold: Float, libraryQualityEvaluator: LibraryQualityEvaluator, explanation: Option[LibrarySearchExplanationBuilder]) extends ResultCollector[ScoreContext] with Logging {

  import UriResultCollector._

  require(matchingThreshold <= 1.0f)

  private[this] val minMatchingThreshold = scala.math.min(matchingThreshold, UriResultCollector.MIN_MATCHING)
  private[this] val myHits = createQueue(maxHitsPerCategory)
  private[this] val friendsHits = createQueue(maxHitsPerCategory)
  private[this] val othersHits = createQueue(maxHitsPerCategory)

  @inline private def isUserCreated(libId: Long): Boolean = {
    LibraryIndexable.getKind(librarySearcher, libId).exists(_ == LibraryKind.USER_CREATED)
  }

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
        val relevantQueue = if ((visibility & Visibility.OWNER) != 0) {
          myHits
        } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
          friendsHits
        } else {
          othersHits
        }
        val popularityBoost = {
          val memberCount = LibraryMembershipIndexable.getMemberCount(libraryMembershipSearcher, id)
          libraryQualityEvaluator.getPopularityBoost(memberCount)
        }
        score = score * popularityBoost
        if ((visibility & (Visibility.OWNER | Visibility.MEMBER)) != 0) { score = score * myLibraryBoost }
        else {
          //todo(Léo): boost libraries if isUserCreated
          val keepCount = libraryQualityEvaluator.estimateKeepCount(keepSearcher, id)
          val publishedLibraryBoost = libraryQualityEvaluator.getPublishedLibraryBoost(keepCount)
          score = score * publishedLibraryBoost
        }
        relevantQueue.insert(id, score, visibility, ctx.secondaryId)
      }

      explanation.foreach { builder =>
        builder.collectRawScore(ctx, matchingThreshold, minMatchingThreshold)
        builder.collectScore(id, score, myLibraryBoost)
      }
    }
  }

  def getResults(): (HitQueue, HitQueue, HitQueue) = (myHits, friendsHits, othersHits)
}
