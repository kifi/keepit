package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.currentDateTime
import com.keepit.curator.model.{ PublicFeedRepo, UriRecommendationRepo }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.util.{ Failure, Random }

class RecommendationCleanupCommander @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    feedsRepo: PublicFeedRepo) {

  private val defaultLimitNumRecosForUser = 500
  def cleanupLowMasterScoreRecos(overrideLimit: Option[Int] = None, overrideTimeCutoff: Option[DateTime] = None): Unit = {
    val userToClean = Random.shuffle(db.readOnlyReplica { implicit session => uriRecoRepo.getUsersWithRecommendations() }.toSeq).take(50)
    db.readWriteBatch(userToClean) { (session, userId) =>
      uriRecoRepo.cleanupLowMasterScoreRecos(userId, overrideLimit.getOrElse(defaultLimitNumRecosForUser), overrideTimeCutoff.getOrElse(currentDateTime.minusDays(4)))(session)
    }.foreach {
      case (userId, res) =>
        res match {
          case Failure(ex) => throw ex
          case _ =>
        }
    }
  }
}
