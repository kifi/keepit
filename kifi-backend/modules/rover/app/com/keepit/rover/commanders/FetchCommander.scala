package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.rover.manager.{ FetchTaskQueue, FetchTask }
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import com.keepit.common.core._

@Singleton
class FetchCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    topPriorityQueue: FetchTaskQueue.TopPriority,
    private implicit val executionContext: ExecutionContext) extends Logging {

  def add(tasks: Seq[FetchTask], queue: FetchTaskQueue): Future[Map[FetchTask, Try[Unit]]] = {
    val maybeQueuedTaskFutures: Seq[Future[(FetchTask, Try[Unit])]] = tasks.map { task =>
      val futureMessage = queue.queue.send(task)
      futureMessage.imap { _ => task -> Success(()) } recover {
        case error: Throwable =>
          log.error(s"Failed to add $task to queue $queue", error)
          task -> Failure(error)
      }
    }
    Future.sequence(maybeQueuedTaskFutures).map { maybeQueuedTasks =>
      val queuedTasks = maybeQueuedTasks.collect { case (task, Success(())) => task }
      val queuedTaskCount = queuedTasks.length
      val failureCount = tasks.length - queuedTaskCount
      if (failureCount > 0) { log.error(s"Failed to add $failureCount tasks to $queue") }
      if (queuedTaskCount > 0) {
        // queues should be configured to have a very short delivery delay to make sure tasks are marked before they are consumed
        db.readWrite { implicit session =>
          articleInfoRepo.markAsQueued(queuedTasks.map(_.id): _*)
        }
        log.info(s"Added $queuedTaskCount tasks to $queue")
      }
      maybeQueuedTasks.toMap
    }
  }

  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForFetching(limit, queuedForMoreThan)
    }
  }

  def fetchWithTopPriority(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
    val tasks = ids.map(FetchTask(_)).toSeq
    add(tasks, topPriorityQueue).imap { _.map { case (task, result) => (task.id -> result) } }
  }
}
