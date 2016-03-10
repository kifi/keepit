package com.keepit.normalizer

import com.google.inject.{ Inject }
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURIRepo
import com.keepit.queue.NormalizationUpdateTask
import com.kifi.franz.{ SQSMessage, SQSQueue }
import scala.concurrent.duration._
import com.keepit.common.core._
import com.kifi.juggle._
import scala.concurrent.{ Future, ExecutionContext }

object NormalizationUpdatingActor {
  val maxBatchSize = 50
  val lockTasksFor = 10 minutes
}

class NormalizationUpdatingActor @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    airbrake: AirbrakeNotifier,
    queue: SQSQueue[NormalizationUpdateTask],
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with BatchProcessingActor[SQSMessage[NormalizationUpdateTask]] {

  import NormalizationUpdatingActor._

  protected val logger = log.logger

  protected def nextBatch: Future[Seq[SQSMessage[NormalizationUpdateTask]]] = {
    queue.nextBatchWithLock(maxBatchSize, lockTasksFor)
  }

  protected def processBatch(batch: Seq[SQSMessage[NormalizationUpdateTask]]): Future[Unit] = {
    val uriById = db.readOnlyMaster { implicit session =>
      uriRepo.getByIds(batch.map(_.body.uriId).toSet)
    }
    FutureHelpers.sequentialExec(batch) { message =>
      val task = message.body
      val update = uriById.get(task.uriId) match {
        case None => Future.successful(())
        case Some(uri) => normalizationService.update(NormalizationReference(uri, task.isNew), task.candidates).imap(_ => ())
      }
      update.imap(_ => message.consume)
    }
  }
}
