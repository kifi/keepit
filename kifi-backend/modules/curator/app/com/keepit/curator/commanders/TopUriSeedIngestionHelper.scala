package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
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
    lastTopUriIngestionRepo: LastTopUriIngestionRepo,
    airbrake: AirbrakeNotifier,
    db: Database,
    graph: GraphServiceClient) extends PersonalSeedIngestionHelper with Logging {

  //re-ingest top uris for a user should be more than 12 hours later.
  val uriIngestionFreq = 4

  private def updateUserTrackItem(userTrackItem: LastTopUriIngestion, lastSeen: DateTime)(implicit session: RWSession): Unit = {
    lastTopUriIngestionRepo.save(userTrackItem.copy(
      lastIngestionTime = lastSeen))
  }

  def processUriScores(uriScore: ConnectedUriScore, userId: Id[User])(implicit session: RWSession): Unit = {
    rawSeedsRepo.getByUriIdAndUserId(uriScore.uriId, Some(userId)) match {
      case Some(seedItem) => {
        rawSeedsRepo.save(seedItem.copy(
          priorScore = Some(uriScore.score.toFloat),
          lastSeen = currentDateTime))
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
            timesKept = anotherRawSeedItem.timesKept,
            discoverable = anotherRawSeedItem.discoverable
          ))

          case None => {
            log.info(s"Can't find another Raw Seed Item. Must have been renormalized. UriId: ${uriScore.uriId}")
          }
        }

      }
    }
  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  //maxItems not used for getListOfUriAndScorePair API, always return false
  def apply(userId: Id[User], maxItems: Int): Future[Boolean] = {
    var firstTimeIngesting = false
    val lastIngestionTime = db.readOnlyMaster { implicit session =>
      lastTopUriIngestionRepo.getByUserId(userId) match {
        case Some(userTrack) => userTrack.lastIngestionTime
        case None => {
          firstTimeIngesting = true
          currentDateTime
        }
      }
    }

    val betweenHours = Hours.hoursBetween(lastIngestionTime, currentDateTime).getHours

    if (betweenHours > uriIngestionFreq || firstTimeIngesting) {
      graph.getConnectedUriScores(userId, avoidFirstDegreeConnections = true).flatMap { uriScores =>
        db.readWriteAsync { implicit session =>
          if (firstTimeIngesting) {
            lastTopUriIngestionRepo.save(LastTopUriIngestion(userId = userId, lastIngestionTime = currentDateTime))
          } else {
            lastTopUriIngestionRepo.getByUserId(userId) match {
              case Some(lastTopUriIngestion) => updateUserTrackItem(lastTopUriIngestion, currentDateTime)
              case None => {
                log.error("Can't find lastTopUriIngestion.")
                airbrake.notify("Can't find lastTopUriIngestion.")
              }
            }
          }

          uriScores.foreach { uriScore =>
            log.debug(s"ingesting uri score is: ${uriScore.score.toFloat}, related user id is: ${uriScore.uriId.toString}")
            processUriScores(uriScore, userId)
          }

          false
        }
      }.recover {
        case ex: Exception =>
          airbrake.notify(s"Could not get uris from graph, skipping user $userId", ex)
          false
      }
    } else {
      Future.successful(false)
    }

  }

}
