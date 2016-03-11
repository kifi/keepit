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
  val minConcurrentUpdates = 10
  val maxConcurrentUpdates = 50
  val lockTasksFor = 10 minutes
}

class NormalizationUpdatingActor @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    airbrake: AirbrakeNotifier,
    queue: SQSQueue[NormalizationUpdateTask],
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[SQSMessage[NormalizationUpdateTask]] {

  import NormalizationUpdatingActor._

  protected val minConcurrentTasks = minConcurrentUpdates
  protected val maxConcurrentTasks = maxConcurrentUpdates

  protected def pullTasks(limit: Int): Future[Seq[SQSMessage[NormalizationUpdateTask]]] = {
    queue.nextBatchWithLock(limit, lockTasksFor)
  }

  protected def processTasks(messages: Seq[SQSMessage[NormalizationUpdateTask]]): Map[SQSMessage[NormalizationUpdateTask], Future[Unit]] = {
    val uriById = db.readOnlyMaster { implicit session =>
      uriRepo.getByIds(messages.map(_.body.uriId).toSet)
    }
    messages.map { message =>
      val update = uriById.get(message.body.uriId) match {
        case None => Future.successful(())
        case Some(uri) => normalizationService.update(NormalizationReference(uri, message.body.isNew), message.body.candidates).imap(_ => ())
      }
      message -> update.imap(_ => message.consume())
    }.toMap
  }
}
