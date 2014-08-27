package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.currentDateTime
import com.keepit.curator.model.{ PublicFeedRepo, UriRecommendationRepo }
import org.joda.time.DateTime
import com.keepit.common.time._

class RecommendationCleanupCommander @Inject() (
    db: Database,
    uriRecRepo: UriRecommendationRepo,
    feedsRepo: PublicFeedRepo) {

  private val defaultLimitNumRecosForUser = 1000
  def cleanupLowMasterScoreRecos(limitNumRecosForUser: Option[Int] = Some(defaultLimitNumRecosForUser), before: Option[DateTime] = Some(currentDateTime.minusDays(14))): Boolean = {
    db.readWrite { implicit session =>
      uriRecRepo.cleanupLowMasterScoreRecos(limitNumRecosForUser.getOrElse(defaultLimitNumRecosForUser), before.getOrElse(currentDateTime.minusDays(14)))
    }
  }

  private val defaultLimitNumFeeds = 5000
  def cleanupLowMasterScoreFeeds(limitNumRecosForUser: Option[Int] = Some(defaultLimitNumFeeds), before: Option[DateTime] = Some(currentDateTime.minusDays(14))): Boolean = {
    db.readWrite { implicit session =>
      feedsRepo.cleanupLowMasterScoreFeeds(limitNumRecosForUser.getOrElse(defaultLimitNumFeeds), before.getOrElse(currentDateTime.minusDays(14)))
    }
  }
}
