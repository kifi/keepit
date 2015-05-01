package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.currentDateTime
import com.keepit.curator.model.{ PublicFeedRepo, UriRecommendationRepo }
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.util.{ Failure }
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }

class RecommendationCleanupCommander @Inject() (
    db: Database,
    clock: Clock,
    uriRecoRepo: UriRecommendationRepo,
    feedsRepo: PublicFeedRepo) extends Logging {

  private val recosTTL = 30 // days (by updateAt)
  private val defaultLimitNumRecosForUser = 300
  private val timeSlicer = new TimeSlicer(clock)

  def cleanup(overrideLimit: Option[Int] = None, overrideTimeCutoff: Option[DateTime] = None, useSubset: Boolean = true): Unit = {
    val usersWithReco = db.readOnlyReplica { implicit session => uriRecoRepo.getUsersWithRecommendations() }.toSeq
    val (idx, ringSize) = timeSlicer.getSliceAndSize(TimeToSliceInDays.TWO_WEEKS, OneSliceInMinutes(CuratorTasksPlugin.CLEAN_FREQ))
    val userToClean = if (useSubset) usersWithReco.filter(_.id % ringSize == idx) else usersWithReco

    val timer = new NamedStatsdTimer("RecommendationCleanupCommander.cleanup")
    log.info(s"Running Uri Reco Cleanup for ${userToClean.size} users. slice $idx out of $ringSize. Users are ${userToClean.take(5).mkString(", ")} ...")

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
