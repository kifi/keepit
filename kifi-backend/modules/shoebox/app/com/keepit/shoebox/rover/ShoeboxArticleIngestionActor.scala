package com.keepit.shoebox.rover

import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ ShoeboxArticleUpdate, ArticleInfo, ShoeboxArticleUpdates }
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object ShoeboxArticleIngestionActor {
  val shoeboxArticleInfoSeq = Name[SequenceNumber[ArticleInfo]]("shoebox_article_info")
  val fetchSize: Int = 50
  sealed trait ShoeboxArticleIngestionActorMessage
  case object StartIngestion extends ShoeboxArticleIngestionActorMessage
  case class DoneIngesting(mayHaveMore: Boolean) extends ShoeboxArticleIngestionActorMessage
  case object CancelIngestion extends ShoeboxArticleIngestionActorMessage
}

class ShoeboxArticleIngestionActor @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    pageInfoRepo: PageInfoRepo,
    articleNormalizer: ArticleUpdateNormalizer,
    systemValueRepo: SystemValueRepo,
    rover: RoverServiceClient,
    airbrake: AirbrakeNotifier,
    private implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import ShoeboxArticleIngestionActor._

  private[this] var ingesting = false

  def receive = {
    case ingestionMessage: ShoeboxArticleIngestionActorMessage => {
      ingestionMessage match {
        case StartIngestion =>
          if (!ingesting) {
            startIngestion()
          }
        case CancelIngestion => endIngestion()
        case DoneIngesting(mayHaveMore) => if (mayHaveMore) startIngestion() else endIngestion()
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startIngestion(): Unit = {
    ingesting = true
    log.info(s"Starting ingestion of Shoebox Article updates from Rover...")
    val seqNum = db.readOnlyMaster { implicit session =>
      systemValueRepo.getSequenceNumber(shoeboxArticleInfoSeq) getOrElse SequenceNumber.ZERO
    }
    rover.getShoeboxUpdates(seqNum, fetchSize).flatMap {
      case Some(ShoeboxArticleUpdates(updates, maxSeq)) if updates.nonEmpty =>
        FutureHelpers.sequentialExec(updates)(ingest).map { _ =>
          db.readWrite { implicit session =>
            updates.map(_.uriId).distinct.foreach { uriId =>
              uriRepo.save(uriRepo.get(uriId)) // increment sequence numbers for other services
            }
            systemValueRepo.setSequenceNumber(shoeboxArticleInfoSeq, maxSeq)
          }
          updates.length
        }
      case _ => Future.successful(0)
    } onComplete {
      case Failure(error) => {
        log.error("Failed to ingest Shoebox Article updates from Rover.", error)
        self ! CancelIngestion
      }
      case Success(ingestedUpdateCount) => {
        log.info(s"Ingested $ingestedUpdateCount Shoebox Article updates from Rover.")
        self ! DoneIngesting(mayHaveMore = ingestedUpdateCount > 0)
      }
    }
  }

  private def endIngestion(): Unit = {
    ingesting = false
    log.info(s"Article Update Ingestion ended.")
  }

  private def ingest(update: ShoeboxArticleUpdate): Future[Unit] = {
    if (update.sensitive) { recordSensitiveUri(update.uriId) }
    articleNormalizer.processUpdate(update)
  }

  private def recordSensitiveUri(uriId: Id[NormalizedURI]): Unit = {
    db.readWrite { implicit session =>
      val uri = uriRepo.get(uriId)
      val restrictedUri = uri.copy(restriction = Some(Restriction.ADULT))
      if (uri != restrictedUri) { uriRepo.save(restrictedUri) }
      pageInfoRepo.getByUri(uriId).foreach { pageInfo =>
        val restrictedPageInfo = pageInfo.copy(safe = Some(false))
        if (pageInfo != restrictedPageInfo) { pageInfoRepo.save(restrictedPageInfo) }
      }
    }
  }
}
