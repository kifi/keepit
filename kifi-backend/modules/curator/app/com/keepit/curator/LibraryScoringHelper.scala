package com.keepit.curator

import java.util.concurrent.TimeUnit

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.commanders.LibraryRecoCandidate
import com.keepit.curator.model.{ CuratorLibraryMembershipInfoRepo, LibraryScores, CuratorLibraryInfo }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.LibraryAccess
import org.joda.time.Interval

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.Duration

case class ScoredLibraryInfo(libraryInfo: CuratorLibraryInfo, masterScore: Float, allScores: LibraryScores) {
  val libraryId = libraryInfo.libraryId
}

class LibraryScoringHelper @Inject() (
    db: Database,
    graph: GraphServiceClient,
    cortex: CortexServiceClient,
    libMembershipRepo: CuratorLibraryMembershipInfoRepo) {

  def apply(libraries: Seq[LibraryRecoCandidate]): Future[Seq[ScoredLibraryInfo]] = {
    Future.sequence(libraries map { candidate =>
      val socialScoreF = getSocialScore(candidate)
      val interestScoreF = getInterestScore(candidate)

      for {
        socialScore <- socialScoreF
        interestScore <- interestScoreF
      } yield {
        val allScores = LibraryScores(
          socialScore = socialScore,
          recencyScore = getRecencyScore(candidate),
          interestScore = interestScore,
          popularityScore = getPopularityScore(candidate),
          sizeScore = getSizeScore(candidate)
        )

        ScoredLibraryInfo(libraryInfo = candidate.libraryInfo,
          masterScore = computeMasterScore(allScores), allScores = allScores)
      }
    })
  }

  // default weights
  val interestScoreWeight = 1f
  val recencyScoreWeight = 0.85f
  val socialScoreWeight = 0.95f
  val popularityScoreWeight = 0.9f
  val sizeScoreWeight = 0.8f

  private def computeMasterScore(allScores: LibraryScores): Float = {
    // TODO(josh) initial weights are arbitrary and will need updating
    allScores.interestScore * interestScoreWeight +
      allScores.recencyScore * recencyScoreWeight +
      allScores.socialScore * socialScoreWeight +
      allScores.popularityScore * popularityScoreWeight +
      allScores.sizeScore * sizeScoreWeight
  }

  // TODO(josh) replace with batch endpoint when one exists
  private def getInterestScore(candidate: LibraryRecoCandidate): Future[Float] = {
    cortex.userLibraryScore(candidate.userId, candidate.libraryId)(None) map {
      case Some(score) if score > 0.25f => score // TODO(josh) guard may need refinement
      case _ => 0f
    }
  }

  val maxIntervalMillis: Long = Duration(7, TimeUnit.DAYS).toMillis

  // uses the lastKept date of the library to determine recency
  // TODO(josh) consider date the library was created either for this score or another
  private def getRecencyScore(candidate: LibraryRecoCandidate): Float = {
    candidate.libraryInfo.lastKept.map { keptDate =>
      val interval = new Interval(keptDate.toInstant, currentDateTime.toInstant)
      ((maxIntervalMillis - Math.min(interval.toDurationMillis, maxIntervalMillis)) / maxIntervalMillis).toFloat
    } getOrElse 0f
  }

  private def getSocialScore(candidate: LibraryRecoCandidate): Future[Float] = {
    graph.getConnectedUserScores(candidate.userId, avoidFirstDegreeConnections = false).map { socialScores =>
      val socialScoreMap = socialScores.map { socialScore =>
        (socialScore.userId, socialScore.score.toFloat)
      }.toMap

      // weight scores different if friend follows vs owns
      val memberships = db.readOnlyReplica { implicit s =>
        libMembershipRepo.getByLibrary(candidate.libraryInfo.libraryId)
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

  private def getPopularityScore(candidate: LibraryRecoCandidate): Float = {
    1 / (1 + Math.exp(-candidate.libraryInfo.memberCount / 5)).toFloat
  }

  private def getSizeScore(candidate: LibraryRecoCandidate): Float = {
    1 / (1 + Math.exp(-candidate.libraryInfo.keepCount / 15)).toFloat
  }

}
