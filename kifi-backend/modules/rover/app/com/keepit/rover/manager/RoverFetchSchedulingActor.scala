package com.keepit.rover.manager

import com.google.inject.{ Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.rover.article.ArticleCommander
import com.keepit.rover.model.{ RoverArticleInfo }
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure }

object RoverFetchSchedulingActor {
  val maxBatchSize = 15 // low to balance producer / consumer behavior *on leader* (SQS send / receive), increase if we don't care about leader as a consumer.
  val maxQueuedFor = 12 hours
}

class RoverFetchSchedulingActor @Inject() (
    articleCommander: ArticleCommander,
    airbrake: AirbrakeNotifier,
    firstTimeQueue: FetchTaskQueue.FirstTime,
    newVersionQueue: FetchTaskQueue.NewVersion,
    refreshQueue: FetchTaskQueue.Refresh,
    implicit val executionContext: ExecutionContext) extends BatchProcessingActor[RoverArticleInfo](airbrake) with Logging {

  import RoverFetchSchedulingActor._

  protected def nextBatch: Future[Seq[RoverArticleInfo]] = {
    SafeFuture {
      log.info(s"Queuing up to $maxBatchSize article fetch tasks...")
      articleCommander.getRipeForFetching(maxBatchSize, maxQueuedFor)
    }
  }

  protected def processBatch(batch: Seq[RoverArticleInfo]): Future[Unit] = {
    val maybeQueuedFutures = batch.groupBy(getRelevantQueue).map {
      case (queue, articleInfos) =>
        val tasks = articleInfos.map { articleInfo => FetchTask(articleInfo.id.get) }
        articleCommander.add(tasks, queue)
    }
    Future.sequence(maybeQueuedFutures).imap { maybeQueuedByQueue =>
      val maybeQueued = maybeQueuedByQueue.flatten
      val queuedCount = maybeQueued.count(_._2.isSuccess)
      (queuedCount, maybeQueued.size)
    } andThen {
      case Success((queuedTaskCount, totalTaskCount)) => log.info(s"Added $queuedTaskCount / $totalTaskCount article fetch tasks.")
      case Failure(error) => log.error(s"Failed to add article fetch tasks.", error)
    }
  } imap { _ => () }

  private def getRelevantQueue(articleInfo: RoverArticleInfo): FetchTaskQueue = articleInfo.latestVersion match {
    case None => firstTimeQueue
    case Some(version) => if (version.major < articleInfo.articleKind.version) newVersionQueue else refreshQueue
  }
}
