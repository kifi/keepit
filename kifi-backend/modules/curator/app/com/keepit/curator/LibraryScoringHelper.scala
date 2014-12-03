package com.keepit.curator

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.curator.commanders.LibraryRecoCandidate
import com.keepit.curator.model.{ CuratorLibraryMembershipInfoRepo, LibraryScores, CuratorLibraryInfo }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.LibraryAccess
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.Interval

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

case class ScoredLibraryInfo(libraryInfo: CuratorLibraryInfo, masterScore: Float, allScores: LibraryScores) {
  val libraryId = libraryInfo.libraryId
}

class LibraryScoringHelper @Inject() (
    db: Database,
    graph: GraphServiceClient,
    libMembershipRepo: CuratorLibraryMembershipInfoRepo,
    shoebox: ShoeboxServiceClient) {

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

  private def computeMasterScore(allScores: LibraryScores): Float = {
    // TODO(josh)
    allScores.interestScore * 1 +
      allScores.recencyScore * 1 +
      allScores.socialScore * 1 +
      allScores.popularityScore * 1 +
      allScores.sizeScore * 1
  }

  private def getInterestScore(candidate: LibraryRecoCandidate): Future[Float] = {
    Future.successful(1) // TODO(josh)
  }

  val maxIntervalMillis: Long = 60 * 60 * 24 * 7 * 1000 // 7 days

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
