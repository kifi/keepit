package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.UriRecommendationRepo

class RecommendationCleanupCommander @Inject() (
    db: Database,
    uriRecRepo: UriRecommendationRepo) {

  private val defaultLimitNumRecosForUser = 1000
  def cleanupLowMasterScoreRecos(limitNumRecosForUser: Option[Int] = Some(defaultLimitNumRecosForUser)): Boolean = {
    db.readWrite { implicit session =>
      uriRecRepo.cleanupLowMasterScoreRecos(limitNumRecosForUser.getOrElse(defaultLimitNumRecosForUser))
    }
  }
}
