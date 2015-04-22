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
    queue.add(tasks).map { maybeQueuedTasks =>
      val queuedTasks = maybeQueuedTasks.collect { case (task, Success(())) => task } toSeq
      if (queuedTasks.nonEmpty) {
        // queues should be configured to have a very short delivery delay to make sure tasks are marked before they are consumed
        db.readWrite { implicit session =>
          articleInfoRepo.markAsFetching(queuedTasks.map(_.id): _*)
        }
      }
      maybeQueuedTasks
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
