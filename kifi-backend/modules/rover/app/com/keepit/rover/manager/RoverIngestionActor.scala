package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.{ State, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.{ Name, IndexableUri, SystemValueRepo, NormalizedURI }
import com.keepit.rover.model.ArticleInfoRepo
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

object RoverIngestionActor {
  val roverNormalizedUriSeq = Name[SequenceNumber[NormalizedURI]]("rover_normalized_uri")
  val fetchSize: Int = 50
  sealed trait RoverIngestionActorMessage
  case object StartIngestion extends RoverIngestionActorMessage
  case class Ingest(uris: Seq[IndexableUri], mayHaveMore: Boolean) extends RoverIngestionActorMessage
  case object CancelIngestion extends RoverIngestionActorMessage
}

class RoverIngestionActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    systemValueRepo: SystemValueRepo,
    shoebox: ShoeboxServiceClient,
    articlePolicy: ArticleFetchingPolicy,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import RoverIngestionActor._

  private[this] var ingesting = false

  def receive = {
    case ingestionMessage: RoverIngestionActorMessage => {
      ingestionMessage match {
        case StartIngestion =>
          if (!ingesting) {
            startIngestion()
          }
        case CancelIngestion => endIngestion()
        case Ingest(updates, mayHaveMore) =>
          ingest(updates)
          if (mayHaveMore) startIngestion() else endIngestion()
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startIngestion(): Unit = {
    ingesting = true
    log.info(s"Starting ingestion...")
    val seqNum = db.readOnlyMaster { implicit session =>
      systemValueRepo.getSequenceNumber(roverNormalizedUriSeq) getOrElse SequenceNumber.ZERO
    }
    shoebox.getIndexableUris(seqNum, fetchSize).onComplete {
      case Failure(error) => {
        log.error("Could not fetch uri updates", error)
        self ! CancelIngestion
      }
      case Success(uris) => {
        log.info(s"Fetched ${uris.length} uri updates")
        self ! Ingest(uris, mayHaveMore = uris.nonEmpty)
      }
    }
  }

  private def endIngestion(): Unit = {
    ingesting = false
    log.info(s"Ingestion ended.")
  }

  private def ingest(uris: Seq[IndexableUri]): Unit = if (uris.nonEmpty) {
    db.readWrite { implicit session =>
      uris.foreach { uri =>
        articleInfoRepo.internByUri(uri.id.get, uri.url, articlePolicy.toBeInterned(uri.url, uri.state))
        articleInfoRepo.deactivateByUriAndKinds(uri.id.get, articlePolicy.toBeDeactivated(uri.state))
      }
      val maxSeq = uris.map(_.seq).max
      systemValueRepo.setSequenceNumber(roverNormalizedUriSeq, maxSeq)
    }
    log.info(s"Ingested ${uris.length} uri updates")
  }
}
