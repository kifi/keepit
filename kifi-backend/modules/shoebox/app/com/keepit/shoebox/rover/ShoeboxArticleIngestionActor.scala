package com.keepit.shoebox.rover

import com.google.inject.Inject
import com.keepit.common.akka.{ SafeFuture, UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.{ ArticleKind, EmbedlyArticle }
import com.keepit.rover.model.{ ShoeboxArticleUpdate, ArticleInfo, ShoeboxArticleUpdates }
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }
import com.keepit.common.core._

object ShoeboxArticleIngestionActor {
  val shoeboxArticleInfoSeq = Name[SequenceNumber[ArticleInfo]]("shoebox_article_info")
  val fetchSize: Int = 500
  val throttle = new ReactiveLock(50)
  sealed trait ShoeboxArticleIngestionActorMessage
  case object StartIngestion extends ShoeboxArticleIngestionActorMessage
  case class DoneIngesting(mayHaveMore: Boolean) extends ShoeboxArticleIngestionActorMessage
  case object CancelIngestion extends ShoeboxArticleIngestionActorMessage
}

class ShoeboxArticleIngestionActor @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    httpRedirectHelper: HttpRedirectIngestionHelper,
    normalizationInfoHelper: NormalizationInfoIngestionHelper,
    systemValueRepo: SystemValueRepo,
    rover: RoverServiceClient,
    airbrake: AirbrakeNotifier,
    urlPatternRulesRepo: UrlPatternRuleRepo,
    uriIntegrityHelpers: UriIntegrityHelpers,
    private implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import ShoeboxArticleIngestionActor._

  private[this] var ingesting = false

  def receive = {
    case ingestionMessage: ShoeboxArticleIngestionActorMessage => {
      ingestionMessage match {
        case StartIngestion => if (!ingesting) startIngestion()
        case CancelIngestion => endIngestion()
        case DoneIngesting(mayHaveMore) => if (mayHaveMore) startIngestion() else endIngestion()
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startIngestion(): Unit = {
    log.info(s"Starting ingestion of Shoebox Article updates from Rover...")
    ingesting = true

    val futureIngestionResult = SafeFuture {
      db.readOnlyMaster { implicit session =>
        systemValueRepo.getSequenceNumber(shoeboxArticleInfoSeq) getOrElse SequenceNumber.ZERO
      }
    } flatMap { seqNum =>
      rover.getShoeboxUpdates(seqNum, fetchSize).flatMap {
        case Some(ShoeboxArticleUpdates(updates, maxSeq)) =>
          processRedirectsAndNormalizationInfo(updates).map { partiallyProcessedUpdatesByUri =>
            db.readWrite { implicit session =>
              updateActiveUris(partiallyProcessedUpdatesByUri)
              systemValueRepo.setSequenceNumber(shoeboxArticleInfoSeq, maxSeq)
            }
            (updates.length, seqNum, maxSeq)
          }
        case None => Future.successful((0, seqNum, seqNum))
      }
    }

    futureIngestionResult onComplete {
      case Failure(error) => {
        val msg = "Failed to ingest Shoebox Article updates from Rover."
        log.error(msg, error)
        airbrake.notify(msg, error)
        self ! CancelIngestion
      }
      case Success((ingestedUpdateCount, initialSeqNum, updatedSeqNum)) => {
        log.info(s"Ingested $ingestedUpdateCount Shoebox Article updates from Rover (seq $initialSeqNum to $updatedSeqNum)")
        self ! DoneIngesting(mayHaveMore = updatedSeqNum > initialSeqNum)
      }
    }
  }

  private def endIngestion(): Unit = {
    ingesting = false
    log.info(s"Article Update Ingestion ended.")
  }

  private def processRedirectsAndNormalizationInfo(updates: Seq[ShoeboxArticleUpdate]): Future[Iterable[(Id[NormalizedURI], Seq[ShoeboxArticleUpdate], Boolean)]] = {
    val haveBeenRenormalizedFutures = updates.groupBy(_.uriId).map {
      case (uriId, updates) =>
        processRedirectsAndNormalizationInfo(uriId, updates).imap {
          hasBeenRenormalized => (uriId, updates, hasBeenRenormalized)
        }
    }
    Future.sequence(haveBeenRenormalizedFutures)
  }

  private def processRedirectsAndNormalizationInfo(uriId: Id[NormalizedURI], updates: Seq[ShoeboxArticleUpdate]): Future[Boolean] = {
    require(updates.forall(_.uriId == uriId), s"Updates do not match expecting uriId ($uriId): $updates")
    throttle.withLockFuture {
      FutureHelpers.exists(updates)(processRedirectsAndNormalizationInfo)
    }
  }

  private def processRedirectsAndNormalizationInfo(update: ShoeboxArticleUpdate): Future[Boolean] = {
    processRedirects(update) flatMap {
      case false => processNormalizationInfo(update)
      case true => Future.successful(true)
    }
  }

  private def processRedirects(update: ShoeboxArticleUpdate): Future[Boolean] = update.httpInfo match {
    case None => Future.successful(false)
    case Some(httpInfo) => httpRedirectHelper.processRedirects(update.uriId, update.url, httpInfo.redirects, update.createdAt)
  }

  private def processNormalizationInfo(update: ShoeboxArticleUpdate): Future[Boolean] = update.normalizationInfo match {
    case Some(normalizationInfo) => normalizationInfoHelper.processNormalizationInfo(update.uriId, update.articleKind, update.destinationUrl, normalizationInfo)
    case None => Future.successful(false)
  }

  private def updateActiveUris(partiallyProcessedUpdatesByUri: Iterable[(Id[NormalizedURI], Seq[ShoeboxArticleUpdate], Boolean)])(implicit session: RWSession): Unit = {
    if (partiallyProcessedUpdatesByUri.nonEmpty) {
      val rules = urlPatternRulesRepo.getUrlPatternRules()
      partiallyProcessedUpdatesByUri.foreach {
        case (uriId, updates, hasBeenRenormalized) =>
          if (!hasBeenRenormalized) { updateUriAndKeeps(uriId, updates, rules) }
      }
    }
  }

  private def updateUriAndKeeps(uriId: Id[NormalizedURI], updates: Seq[ShoeboxArticleUpdate], rules: UrlPatternRules)(implicit session: RWSession): Unit = {
    require(updates.forall(_.uriId == uriId), s"Updates do not match expecting uriId ($uriId): $updates")
    log.info(s"Updating NormalizedURI ($uriId) after processing associated ShoeboxArticleUpdates from Rover: $updates")
    val uri = uriRepo.get(uriId)
    val fetchedTitles = updates.map(update => (update.articleKind -> update.title)).collect {
      case (kind, Some(fetchedTitle)) if fetchedTitle.nonEmpty => (kind -> fetchedTitle)
    }.toMap[ArticleKind[_], String]
    // always use Embedly's title otherwise update title if empty
    val preferredTitle = fetchedTitles.get(EmbedlyArticle) orElse uri.title orElse fetchedTitles.values.headOption

    val restriction = {
      val isSensitiveByRule = updates.foldLeft[Option[Boolean]](None) {
        case (Some(true), _) => Some(true)
        case (isSensitiveByRuleSoFar, update) => rules.isSensitive(update.destinationUrl) orElse isSensitiveByRuleSoFar
      }

      isSensitiveByRule match {
        case Some(true) => Some(Restriction.ADULT)
        case Some(false) => uri.restriction.filterNot(_ == Restriction.ADULT)
        case None => if (updates.exists(_.sensitive)) Some(Restriction.ADULT) else uri.restriction
      }
    }

    val state = if (uri.state == NormalizedURIStates.ACTIVE) NormalizedURIStates.SCRAPED else uri.state

    val updatedUri = uriRepo.save(uri.copy(title = preferredTitle, restriction = restriction, state = state)) // always save to increment sequence numbers for other services

    updatedUri tap uriIntegrityHelpers.improveKeepsSafely
  }
}
