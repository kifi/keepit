package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.rover.manager.{ FetchTaskQueue, FetchTask }
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Try }
import com.keepit.common.core._

@Singleton
class FetchCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    topPriorityQueue: FetchTaskQueue.TopPriority,
    private implicit val executionContext: ExecutionContext) extends Logging {

  def add(tasks: Seq[FetchTask], queue: FetchTaskQueue): Future[Map[FetchTask, Try[Unit]]] = {
    db.readWrite { implicit session =>
      articleInfoRepo.markAsFetching(tasks.map(_.id): _*)
    }
    queue.add(tasks).map { maybeQueuedTasks =>
      val failedTasks = maybeQueuedTasks.collect { case (task, Failure(_)) => task }.toSeq
      if (failedTasks.nonEmpty) {
        db.readWrite { implicit session =>
          articleInfoRepo.unmarkAsFetching(failedTasks.map(_.id): _*)
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
