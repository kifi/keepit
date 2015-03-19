package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.{ Database, ExecutionSkipped }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.curator.model._
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.ConnectedUriScore
import com.keepit.model._
import org.joda.time.{ Seconds, Hours, DateTime }
import com.keepit.common.concurrent.ReactiveLock

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.Random

@Singleton
class TopUriSeedIngestionHelper @Inject() (
    rawSeedsRepo: RawSeedItemRepo,
    lastTopUriIngestionRepo: LastTopUriIngestionRepo,
    airbrake: AirbrakeNotifier,
    db: Database,
    graph: GraphServiceClient) extends PersonalSeedIngestionHelper with Logging {

  val uriIngestionFreq = 180 //hours

  val graphCallLimiterLock = new ReactiveLock(2)

  private def updateUserTrackItem(userTrackItem: LastTopUriIngestion, lastSeen: DateTime)(implicit session: RWSession): Unit = {
    lastTopUriIngestionRepo.save(userTrackItem.copy(
      lastIngestionTime = lastSeen))
  }

  def processUriScores(uriId: Id[NormalizedURI], score: Float, userId: Id[User])(implicit session: RWSession): Unit = {
    log.info(s"[graph ingest] $uriId $userId")
    rawSeedsRepo.getByUriIdAndUserId(uriId, Some(userId)) match {
      case Some(seedItem) => {
        rawSeedsRepo.save(seedItem.copy(
          priorScore = Some(score),
          lastSeen = currentDateTime))
      }
      case None => {
        rawSeedsRepo.getFirstByUriId(uriId) match {
          case Some(anotherRawSeedItem) => rawSeedsRepo.save(RawSeedItem(
            uriId = uriId,
            url = anotherRawSeedItem.url,
            userId = Some(userId),
            firstKept = anotherRawSeedItem.firstKept,
            lastKept = anotherRawSeedItem.lastKept,
            lastSeen = currentDateTime,
            priorScore = Some(score),
            timesKept = anotherRawSeedItem.timesKept,
            discoverable = anotherRawSeedItem.discoverable
          ))

          case None => {
            log.info(s"Can't find another Raw Seed Item. Must have been renormalized. UriId: ${uriId}")
          }
        }

      }
    }
  }

  //triggers ingestions of up to maxItem RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  //maxItems not used for getListOfUriAndScorePair API, always return false
  def apply(userId: Id[User], force: Boolean = false): Future[Boolean] = graphCallLimiterLock.withLockFuture {
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

    val randomShift = Random.nextInt(200) - 100
    val betweenHours = Hours.hoursBetween(lastIngestionTime.plusSeconds(randomShift), currentDateTime).getHours

    if (betweenHours > uriIngestionFreq || firstTimeIngesting || (force && betweenHours > 24)) {
      graph.uriWander(userId, 50000).map { uriScores =>
        val rescaledUriScores = uriScores.mapValues(score => Math.log(score.toDouble + 1).toFloat).toSeq.sortBy {
          case (uriId, score) => -1 * score
        }.take(2000).toMap //Last part (sort + take) is a stop gap
        val normalizationFactor = if (rescaledUriScores.isEmpty) 0.0f else rescaledUriScores.values.max

        db.readWriteBatch(rescaledUriScores.toSeq, attempts = 2) { (session, singleScore) =>
          val (uriId, score) = singleScore
          log.debug(s"ingesting uri score is: ${score}, related user id is: ${uriId}")
          processUriScores(uriId, score / normalizationFactor, userId)(session)
        }.values.foreach { maybeException =>
          try {
            maybeException.get
          } catch {
            case _: ExecutionSkipped => //re-raise any real exceptions
          }
        }

        db.readWrite(attempts = 2) { implicit session =>
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
        }

        for (i <- 0 to 2) {
          db.readWrite(attempts = 2) { implicit session =>
            rawSeedsRepo.cleanupBatch(userId)
          }
        }

        false
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
