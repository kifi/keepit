package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.curator.model._
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.ConnectedUriScore
import com.keepit.model._
import org.joda.time.{ Seconds, Hours, DateTime }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class TopUriSeedIngestionHelper @Inject() (
    rawSeedsRepo: RawSeedItemRepo,
    userTrackRepo: LastTopUriIngestionRepo,
    db: Database,
    graph: GraphServiceClient) extends PersonalSeedIngestionHelper {

  //re-ingest top uris for a user should be more than 12 hours later.
  val uriIngestionFreq = 12

  private def updateRawSeedItem(seedItem: RawSeedItem, priorScore: Option[Float], newDateCandidate: DateTime)(implicit session: RWSession): Unit = {
    rawSeedsRepo.save(seedItem.copy(
      priorScore = priorScore,
      lastSeen = currentDateTime))
  }

  private def updateUserTrackItem(userTrackItem: LastTopUriIngestion, lastSeen: DateTime)(implicit session: RWSession): Unit = {
    userTrackRepo.save(userTrackItem.copy(
      lastIngestionTime = lastSeen))
  }

  def processUriScores(uriScore: ConnectedUriScore, userId: Id[User])(implicit session: RWSession): Unit = {
    rawSeedsRepo.getByUriIdAndUserId(uriScore.uriId, userId) match {
      case Some(seedItem) => {
        updateRawSeedItem(seedItem, Some(uriScore.score.toFloat), currentDateTime)
      }
      case None => {
        rawSeedsRepo.getFirstByUriId(uriScore.uriId) match {
          case Some(anotherRawSeedItem) => rawSeedsRepo.save(RawSeedItem(
            uriId = uriScore.uriId,
            userId = Some(userId),
            firstKept = anotherRawSeedItem.firstKept,
            lastKept = anotherRawSeedItem.lastKept,
            lastSeen = currentDateTime,
            priorScore = Some(uriScore.score.toFloat),
            timesKept = anotherRawSeedItem.timesKept))

          case None => rawSeedsRepo.save(RawSeedItem(
            uriId = uriScore.uriId,
            userId = Some(userId),
            firstKept = currentDateTime,
            lastKept = currentDateTime,
            lastSeen = currentDateTime,
            priorScore = Some(uriScore.score.toFloat),
            timesKept = 0))
        }

      }
    }
  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  //maxItems not used for getListOfUriAndScorePair API, always return false
  def apply(userId: Id[User], maxItems: Int): Future[Boolean] = {

    val lastIngestionTime = db.readOnlyMaster { implicit session =>
      userTrackRepo.getByUserId(userId) match {
        case Some(userTrack) => userTrack.lastIngestionTime
        case None => currentDateTime
      }
    }

    val betweenHours = Hours.hoursBetween(currentDateTime, lastIngestionTime).getHours
    val firstTimeIngesting = (Seconds.secondsBetween(currentDateTime, lastIngestionTime).getSeconds < 1)

    if (betweenHours > uriIngestionFreq || firstTimeIngesting) {
      graph.getListOfUriAndScorePairs(userId, avoidFirstDegreeConnections = true).flatMap { uriScores =>
        db.readWriteAsync { implicit session =>
          if (firstTimeIngesting) {
            userTrackRepo.save(LastTopUriIngestion(userId = userId, lastIngestionTime = currentDateTime))
          } else {
            val userTrack = userTrackRepo.getByUserId(userId).get
            updateUserTrackItem(userTrack, currentDateTime)
          }

          uriScores.foreach { uriScore =>
            processUriScores(uriScore, userId)
          }

          false
        }
      }
    } else {
      Future.successful(false)
    }

  }

}
