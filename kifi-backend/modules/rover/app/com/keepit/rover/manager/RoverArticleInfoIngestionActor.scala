package com.keepit.rover.manager

import com.google.inject.{ Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ Name, IndexableUri, SystemValueRepo, NormalizedURI }
import com.keepit.rover.article.policy.ArticleInfoPolicy
import com.keepit.rover.model.ArticleInfoRepo
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleInfoIngestionActor {
  val roverNormalizedUriSeq = Name[SequenceNumber[NormalizedURI]]("rover_normalized_uri")
  val fetchSize: Int = 50
}

class RoverArticleInfoIngestionActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    systemValueRepo: SystemValueRepo,
    shoebox: ShoeboxServiceClient,
    articlePolicy: ArticleInfoPolicy,
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends BatchProcessingActor[IndexableUri](airbrake) with Logging {

  import RoverArticleInfoIngestionActor._

  protected def nextBatch: Future[Seq[IndexableUri]] = {
    log.info(s"Starting ingestion...")
    SafeFuture {
      db.readOnlyMaster { implicit session =>
        systemValueRepo.getSequenceNumber(roverNormalizedUriSeq) getOrElse SequenceNumber.ZERO
      }
    } flatMap { seqNum =>
      shoebox.getIndexableUris(seqNum, fetchSize)
    }
  } andThen {
    case Success(uris) => log.info(s"Fetched ${uris.length} uri updates")
    case Failure(error) => log.error("Could not fetch uri updates", error)
  }

  protected def processBatch(uris: Seq[IndexableUri]): Future[Unit] = SafeFuture {
    if (uris.nonEmpty) {
      db.readWrite { implicit session =>
        uris.foreach { uri =>
          articleInfoRepo.internByUri(uri.id.get, uri.url, articlePolicy.toBeInterned(uri.url, uri.state))
          articleInfoRepo.deactivateByUriAndKinds(uri.id.get, articlePolicy.toBeDeactivated(uri.state))
        }
        val maxSeq = uris.map(_.seq).max
        systemValueRepo.setSequenceNumber(roverNormalizedUriSeq, maxSeq)
      }
      log.info(s"Ingested ${uris.length} uri updates")
      fetchSchedulingActor.ref ! ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
    }
  }
}
