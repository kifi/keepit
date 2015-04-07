package com.keepit.curator

import java.util.concurrent.TimeUnit

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model.{ LibraryRecoSelectionParams, CuratorLibraryMembershipInfoRepo, LibraryScores, CuratorLibraryInfo }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.{ User, LibraryAccess }
import org.joda.time.Interval

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.Duration

case class ScoredLibraryInfo(libraryInfo: CuratorLibraryInfo, masterScore: Float, allScores: LibraryScores) {
  val libraryId = libraryInfo.libraryId
}

@Singleton
class LibraryScoringHelper @Inject() (
    db: Database,
    graph: GraphServiceClient,
    cortex: CortexServiceClient,
    libMembershipRepo: CuratorLibraryMembershipInfoRepo,
    private val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], libraries: Seq[CuratorLibraryInfo], selectionParams: LibraryRecoSelectionParams): Future[Seq[ScoredLibraryInfo]] = {
    val socialScoresF = getSocialScores(userId, libraries)
    val userLibrariesScoresF = getLibraryInterestScores(userId, libraries)

    for {
      socialScores <- socialScoresF
      interestScores <- userLibrariesScoresF
    } yield {
      (libraries zip interestScores zip socialScores).map {
        case ((candidate, interestScore), socialScore) =>
          val allScores = LibraryScores(
            socialScore = socialScore,
            recencyScore = getRecencyScore(candidate),
            interestScore = interestScore,
            popularityScore = getPopularityScore(candidate),
            sizeScore = getSizeScore(candidate),
            contentScore = Some(getContentScore(candidate)))
          val masterScore = computeMasterScore(allScores, selectionParams)
          ScoredLibraryInfo(candidate, masterScore, allScores)
      }
    }
  }

  private def computeMasterScore(allScores: LibraryScores, selectionParams: LibraryRecoSelectionParams): Float = {
    allScores.interestScore * selectionParams.interestScoreWeight +
      allScores.recencyScore * selectionParams.recencyScoreWeight +
      allScores.socialScore * selectionParams.socialScoreWeight +
      allScores.popularityScore * selectionParams.popularityScoreWeight +
      allScores.sizeScore * selectionParams.sizeScoreWeight +
      allScores.contentScoreOrDefault * selectionParams.contentScoreWeight
  }

  private def getLibraryInterestScores(userId: Id[User], candidates: Seq[CuratorLibraryInfo]): Future[Seq[Float]] = {
    cortex.userLibrariesScores(userId, candidates.map(_.libraryId))(None) map { scores =>
      scores map {
        case Some(score) if score > 0.25f => score // TODO(josh) guard may need refinement
        case _ => 0f
      }
    }
  } recover {
    case ex: Throwable =>
      airbrake.notify(s"LibraryScoringHelper failed getLibraryInterestScores(userId=$userId)", ex)
      Seq.fill(candidates.size)(0f)
  }

  val maxIntervalMillis: Long = Duration(30, TimeUnit.DAYS).toMillis

  // uses the lastKept date of the library to determine recency
  // TODO(josh) consider date the library was created either for this score or another
  private def getRecencyScore(candidate: CuratorLibraryInfo): Float = {
    candidate.lastKept.map { keptDate =>
      val interval = if (keptDate.isBefore(currentDateTime)) new Interval(keptDate.toInstant, currentDateTime.toInstant).toDurationMillis else 0
      (maxIntervalMillis - Math.min(interval, maxIntervalMillis)).toFloat / maxIntervalMillis
    } getOrElse 0f
  }

  private def getSocialScores(userId: Id[User], candidates: Seq[CuratorLibraryInfo]): Future[Seq[Float]] = {
    graph.getConnectedUserScores(userId, avoidFirstDegreeConnections = false).map { socialScores =>
      val socialScoreMap = socialScores.map { socialScore =>
        (socialScore.userId, socialScore.score.toFloat)
      }.toMap

      candidates.map { candidate =>
        // weight scores different if friend follows vs owns
        val memberships = db.readOnlyReplica { implicit s =>
          libMembershipRepo.getByLibrary(candidate.libraryId)
        } filter { m => socialScoreMap.isDefinedAt(m.userId) }

        var accScore = 0f
        memberships map { membership =>
          // give more weight to the score if the other user owns the library
          val membershipMultiplier = if (membership.access == LibraryAccess.OWNER) 2 else 1
          accScore += socialScoreMap(membership.userId) * membershipMultiplier
        }

        Math.tanh(0.5 * accScore).toFloat
      }
    }
  } recover {
    case ex: Throwable =>
      airbrake.notify(s"LibraryScoringHelper failed getSocialScore(userId=$userId)", ex)
      Seq.fill[Float](candidates.size)(0.0f)
  }

  private def getPopularityScore(candidate: CuratorLibraryInfo): Float = {
    1 / (1 + Math.exp(-candidate.memberCount / 5)).toFloat
  }

  private def getSizeScore(candidate: CuratorLibraryInfo): Float = {
    1 / (1 + Math.exp(-candidate.keepCount / 15)).toFloat
  }

  private def getContentScore(candidate: CuratorLibraryInfo): Float = {
    // prefer >= 20 characters in description
    (candidate.descriptionLength / 10f).min(2f) / 2
  }

}
