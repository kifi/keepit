package com.keepit.rover.manager

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.queue.ProbabilisticMultiQueue
import com.keepit.rover.model.RoverArticleInfo
import com.kifi.franz.SQSQueue
import com.kifi.macros.json
import org.joda.time.DateTime
import com.keepit.common.time._

@json
case class FetchTask(id: Id[RoverArticleInfo], createdAt: DateTime = currentDateTime)

sealed trait FetchTaskQueue {
  def queue: SQSQueue[FetchTask]
}

object FetchTaskQueue {
  case class TopPriority(queue: SQSQueue[FetchTask]) extends FetchTaskQueue
  case class FirstTime(queue: SQSQueue[FetchTask]) extends FetchTaskQueue
  case class NewVersion(queue: SQSQueue[FetchTask]) extends FetchTaskQueue
  case class Refresh(queue: SQSQueue[FetchTask]) extends FetchTaskQueue

  def weight(taskQueue: FetchTaskQueue): Double = taskQueue match {
    case _: TopPriority => 0.50
    case _: FirstTime => 0.30
    case _: NewVersion => 0.15
    case _: Refresh => 0.05
  }
}

@Singleton
class ProbabilisticFetchTaskQueue @Inject() (queues: Set[FetchTaskQueue])
  extends ProbabilisticMultiQueue[FetchTask](queues.map { queue => queue.queue -> FetchTaskQueue.weight(queue) }.toMap)
