package com.keepit.rover.manager

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.queue.ProbabilisticMultiQueue
import com.keepit.rover.model.RoverArticleInfo
import com.kifi.franz.SQSQueue
import com.kifi.macros.json
import org.joda.time.DateTime
import com.keepit.common.time._

@json
case class ArticleImageProcessingTask(id: Id[RoverArticleInfo], createdAt: DateTime = currentDateTime)

sealed trait ArticleImageProcessingTaskQueue extends TaskQueue[ArticleImageProcessingTask] with Logging

object ArticleImageProcessingTaskQueue {
  case class FastFollow(queue: SQSQueue[ArticleImageProcessingTask]) extends ArticleImageProcessingTaskQueue
  case class CatchUp(queue: SQSQueue[ArticleImageProcessingTask]) extends ArticleImageProcessingTaskQueue

  def weight(taskQueue: ArticleImageProcessingTaskQueue): Double = taskQueue match {
    case _: FastFollow => 0.70
    case _: CatchUp => 0.30
  }
}

@Singleton
class ProbabilisticArticleImageProcessingTaskQueue @Inject() (queues: Set[ArticleImageProcessingTaskQueue])
  extends ProbabilisticMultiQueue[ArticleImageProcessingTask](queues.map { queue => queue.queue -> ArticleImageProcessingTaskQueue.weight(queue) }.toMap)
