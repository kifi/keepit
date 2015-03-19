package com.keepit.curator

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.curator.model.LibraryRecommendationRepo
import org.joda.time.{ Duration, DateTime }

import scala.util.{ Failure, Random }

@Singleton
class LibraryRecommendationCleanupCommander @Inject() (
    db: Database,
    libraryRecoRepo: LibraryRecommendationRepo) {

  val defaultMinimumRecommendationsToKeep = 200
  val defaultAgeBeforeCleanup = Duration.standardDays(5)

  def cleanupLowMasterScoreRecos(overrideMinimumRecosToKeep: Option[Int] = None, overrideBeforeUpdatedAt: Option[DateTime] = None) = {
    val usersToClean = Random.shuffle(db.readOnlyReplica { implicit session =>
      libraryRecoRepo.getUsersWithRecommendations()
    }.toSeq).take(50)

    db.readWriteBatch(usersToClean) { (session, userId) =>
      libraryRecoRepo.cleanupLowMasterScoreRecos(
        userId,
        overrideMinimumRecosToKeep.getOrElse(defaultMinimumRecommendationsToKeep),
        overrideBeforeUpdatedAt.getOrElse(overrideBeforeUpdatedAt.getOrElse(currentDateTime.minus(defaultAgeBeforeCleanup)))
      )(session)
    } foreach {
      case (_, Failure(ex)) => throw ex
      case _ =>
    }

  }

}
