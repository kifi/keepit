package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.rover.manager.{ ArticleImageProcessingTask, ArticleImageProcessingTaskQueue }
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Success, Try }
import com.keepit.common.core._

@Singleton
class ImageProcessingCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow,
    private implicit val executionContext: ExecutionContext) extends Logging {

  def add(tasks: Seq[ArticleImageProcessingTask], queue: ArticleImageProcessingTaskQueue): Future[Map[ArticleImageProcessingTask, Try[Unit]]] = {
    queue.add(tasks).map { maybeQueuedTasks =>
      val queuedTasks = maybeQueuedTasks.collect { case (task, Success(())) => task }.toSeq
      if (queuedTasks.nonEmpty) {
        // queues should be configured to have a very short delivery delay to make sure tasks are marked before they are consumed
        db.readWrite { implicit session =>
          articleInfoRepo.markAsImageProcessing(queuedTasks.map(_.id): _*)
        }
      }
      maybeQueuedTasks
    }
  }

  def getRipeForImageProcessing(limit: Int, fetchedForMoreThan: Duration, imageProcessingForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForImageProcessing(limit, fetchedForMoreThan, imageProcessingForMoreThan)
    }
  }

  def processArticleImagesAsap(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
    val tasks = ids.map(ArticleImageProcessingTask(_)).toSeq
    add(tasks, fastFollowQueue).imap { _.map { case (task, result) => (task.id -> result) } }
  }
}
