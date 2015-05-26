package com.keepit.rover.manager

import com.google.inject.{ Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.rover.image.ImageCommander
import com.keepit.rover.model.{ RoverArticleInfo }
import scala.concurrent.duration._
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure }

object RoverArticleImageSchedulingActor {
  val maxBatchSize = 30 // low to balance producer / consumer behavior *on leader* (SQS send / receive), increase if we don't care about leader as a consumer.
  val maxQueuedFor = 2 days // re-schedule image processing if it hasn't actually been completed after a while (increase if large backlog)
  val dueAfterRequestedWithin = 1 minute // schedule image processing if it hasn't been already 1 minute after a fetch (don't race with near-line calls, this recovers when they fail)
}

class RoverArticleImageSchedulingActor @Inject() (
    imageProcessingCommander: ImageCommander,
    airbrake: AirbrakeNotifier,
    fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow,
    catchUpQueue: ArticleImageProcessingTaskQueue.CatchUp,
    private implicit val executionContext: ExecutionContext) extends BatchProcessingActor[RoverArticleInfo](airbrake) with Logging {

  import RoverArticleImageSchedulingActor._

  protected def nextBatch: Future[Seq[RoverArticleInfo]] = {
    SafeFuture {
      log.info(s"Queuing up to $maxBatchSize article image processing tasks...")
      imageProcessingCommander.getArticleInfosForImageProcessing(maxBatchSize, dueAfterRequestedWithin, maxQueuedFor)
    }
  }

  protected def processBatch(batch: Seq[RoverArticleInfo]): Future[Unit] = {
    val maybeQueuedFutures = batch.groupBy(getRelevantQueue).map {
      case (queue, articleInfos) =>
        val tasks = articleInfos.map { articleInfo => ArticleImageProcessingTask(articleInfo.id.get) }
        imageProcessingCommander.add(tasks, queue)
    }
    Future.sequence(maybeQueuedFutures).imap { maybeQueuedByQueue =>
      val maybeQueued = maybeQueuedByQueue.flatten
      val queuedCount = maybeQueued.count(_._2.isSuccess)
      (queuedCount, maybeQueued.size)
    } andThen {
      case Success((queuedTaskCount, totalTaskCount)) => log.info(s"Added $queuedTaskCount / $totalTaskCount article image processing tasks.")
      case Failure(error) => log.error(s"Failed to add article image processing tasks.", error)
    }
  } imap { _ => () }

  private def getRelevantQueue(articleInfo: RoverArticleInfo): ArticleImageProcessingTaskQueue = {
    if (articleInfo.lastImageProcessingVersion.isEmpty) fastFollowQueue
    else catchUpQueue
  }
}
