package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.currentDateTime
import com.keepit.curator.model.{ PublicFeedRepo, UriRecommendationRepo }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.util.{ Failure, Random }
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }

class RecommendationCleanupCommander @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    feedsRepo: PublicFeedRepo) extends Logging {

  private val recosTTL = 30 // days (by updateAt)
  private val defaultLimitNumRecosForUser = 300
  def cleanup(overrideLimit: Option[Int] = None, overrideTimeCutoff: Option[DateTime] = None): Unit = {
    val userToClean = Random.shuffle(db.readOnlyReplica { implicit session => uriRecoRepo.getUsersWithRecommendations() }.toSeq).take(75)
    val timer = new NamedStatsdTimer("RecommendationCleanupCommander.cleanup")
    log.info(s"Running Uri Reco Cleanup for $userToClean")
    db.readWriteBatch(userToClean) { (session, userId) =>
      uriRecoRepo.cleanupOldRecos(userId, currentDateTime.minusDays(recosTTL))(session)
      uriRecoRepo.cleanupLowMasterScoreRecos(userId, overrideLimit.getOrElse(defaultLimitNumRecosForUser), overrideTimeCutoff.getOrElse(currentDateTime.minusDays(3)))(session)
    }.foreach {
      case (userId, res) =>
        res match {
          case Failure(ex) => throw ex
          case _ =>
        }
    }
    timer.stopAndReport(appLog = true)
  }
}
