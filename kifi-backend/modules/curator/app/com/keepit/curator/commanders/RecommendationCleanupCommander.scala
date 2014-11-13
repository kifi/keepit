package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.currentDateTime
import com.keepit.curator.model.{ PublicFeedRepo, UriRecommendationRepo }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.util.Failure

class RecommendationCleanupCommander @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    feedsRepo: PublicFeedRepo) {

  private val defaultLimitNumRecosForUser = 500
  def cleanupLowMasterScoreRecos(overrideLimit: Option[Int] = None, overrideTimeCutoff: Option[DateTime] = None): Unit = {
    val userToClean = db.readOnlyReplica { implicit session => uriRecoRepo.getUsersWithRecommendations() }.toSeq
    db.readWriteBatch(userToClean) { (session, userId) =>
      uriRecoRepo.cleanupLowMasterScoreRecos(userId, overrideLimit.getOrElse(defaultLimitNumRecosForUser), overrideTimeCutoff.getOrElse(currentDateTime.minusDays(7)))(session)
    }.foreach {
      case (userId, res) =>
        res match {
          case Failure(ex) => throw ex
          case _ =>
        }
    }
  }

  private val defaultLimitNumFeeds = 5000
  def cleanupLowMasterScoreFeeds(limitNumFeeds: Option[Int] = Some(defaultLimitNumFeeds), before: Option[DateTime] = Some(currentDateTime.minusDays(14))): Boolean = {
    db.readWrite { implicit session =>
      feedsRepo.cleanupLowMasterScoreFeeds(limitNumFeeds.getOrElse(defaultLimitNumFeeds), before.getOrElse(currentDateTime.minusDays(14)))
    }
  }
}
