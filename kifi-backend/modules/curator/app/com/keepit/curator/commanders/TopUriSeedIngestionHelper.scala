package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.curator.model._
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.ConnectedUriScore
import com.keepit.model._
import org.joda.time.{ Hours, DateTime }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class TopUriSeedIngestionHelper @Inject() (
    rawSeedsRepo: RawSeedItemRepo,
    userTrackRepo: UserTrackItemRepo,
    db: Database,
    graph: GraphServiceClient) extends PersonalSeedIngestionHelper {

  val uriIngestFreq = 12

  private def updateRawSeedItem(seedItem: RawSeedItem, uriId: Id[NormalizedURI], priorScore: Option[Float], newDateCandidate: DateTime)(implicit session: RWSession): Unit = {
    rawSeedsRepo.save(seedItem.copy(
      uriId = uriId, //implicit renormalize :-)
      priorScore = priorScore,
      firstKept = if (newDateCandidate.isBefore(seedItem.firstKept)) newDateCandidate else seedItem.firstKept,
      lastKept = if (newDateCandidate.isAfter(seedItem.lastKept)) newDateCandidate else seedItem.lastKept,
      lastSeen = if (newDateCandidate.isAfter(seedItem.lastSeen)) newDateCandidate else seedItem.lastSeen
    ))
  }

  //need to check if same userID?
  private def updateUserTrackItem(userTrackItem: CuratorUserTrackItem, lastSeen: DateTime)(implicit session: RWSession): Unit = {
    userTrackRepo.save(userTrackItem.copy(
      lastSeen = lastSeen
    ))
  }

  def processUriScores(uriScore: ConnectedUriScore, userId: Id[User])(implicit session: RWSession): Unit = {
    //how does here use getOrElse
    rawSeedsRepo.getByUriIdAndUserId(uriScore.uriId, userId) match {
      case Some(seedItem) => updateRawSeedItem(seedItem, uriScore.uriId, Some(uriScore.score.toFloat), currentDateTime)
      case None => {
        val anotherRawSeedItem = rawSeedsRepo.getByUriId(uriScore.uriId).head
        rawSeedsRepo.save(RawSeedItem(
          uriId = uriScore.uriId,
          userId = None,
          firstKept = anotherRawSeedItem.firstKept,
          lastKept = anotherRawSeedItem.lastKept,
          lastSeen = currentDateTime,
          priorScore = Some(uriScore.score.toFloat),
          timesKept = anotherRawSeedItem.timesKept))
      }
    }
  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  //maxItems not used for getListOfUriAndScorePair API, always return true
  def apply(userId: Id[User], maxItems: Int): Future[Boolean] = {

    db.readWriteAsync { implicit session =>
      val userTrack = userTrackRepo.getByUserId(userId) match {
        case Some(userTrack) => userTrack
        case None => userTrackRepo.save(CuratorUserTrackItem(userId = userId, lastSeen = currentDateTime))
      }

      val betweenHours = Hours.hoursBetween(currentDateTime, userTrack.lastSeen).getHours

      if (betweenHours > uriIngestFreq) {
        updateUserTrackItem(userTrack, currentDateTime)
        graph.getListOfUriAndScorePairs(userId, true).map { uriScores =>
          db.readWriteAsync { implicit session =>
            uriScores.foreach(uriScore =>
              processUriScores(uriScore, userId))
          }
        }
      }
    }

    Future(true)
  }

}
