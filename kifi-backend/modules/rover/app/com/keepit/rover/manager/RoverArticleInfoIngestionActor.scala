package com.keepit.rover.manager

import com.google.inject.{ Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.db.{ SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ Name, IndexableUri, SystemValueRepo, NormalizedURI }
import com.keepit.rover.article.ArticleCommander
import com.keepit.rover.article.policy.ArticleFetchPolicy
import com.keepit.rover.model.{ ArticleInfoHelper, ArticleInfoRepo }
import com.keepit.shoebox.ShoeboxServiceClient
import com.kifi.juggle._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleInfoIngestionActor {
  val roverNormalizedUriSeq = Name[SequenceNumber[NormalizedURI]]("rover_normalized_uri")
  val fetchSize: Int = 50
}

class RoverArticleInfoIngestionActor @Inject() (
    db: Database,
    articleInfoHelper: ArticleInfoHelper,
    systemValueRepo: SystemValueRepo,
    shoebox: ShoeboxServiceClient,
    articlePolicy: ArticleFetchPolicy,
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with BatchProcessingActor[IndexableUri] {

  import RoverArticleInfoIngestionActor._

  protected val logger = log.logger

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
    case Failure(error) => log.error("Could not fetch uri updates", error)
  }

  protected def processBatch(uris: Seq[IndexableUri]): Future[Unit] = SafeFuture {
    if (uris.nonEmpty) {
      db.readWrite { implicit session =>
        uris.foreach { uri =>
          articleInfoHelper.intern(uri.url, Some(uri.id.get), articlePolicy.toBeInterned(uri))
          articleInfoHelper.deactivate(uri.url, articlePolicy.toBeDeactivated(uri))
        }
        val maxSeq = uris.map(_.seq).max
        systemValueRepo.setSequenceNumber(roverNormalizedUriSeq, maxSeq)
      }
      log.info(s"Ingested ${uris.length} uri updates")
      fetchSchedulingActor.ref ! ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
    }
  }
}
