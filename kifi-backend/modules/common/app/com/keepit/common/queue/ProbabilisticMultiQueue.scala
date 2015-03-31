package com.keepit.common.queue

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.Logging
import com.keepit.common.math.ProbabilityDensityBuilder
import com.keepit.common.CollectionHelpers._
import com.kifi.franz.{ SQSMessage, SQSQueue }

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import com.keepit.common.core._

class ProbabilisticMultiQueue[T](weights: Map[SQSQueue[T], Double]) extends Logging {
  private val sortedQueues = weights.keySet.toSeq.sortBy(-weights(_))
  private val offsetDensity = {
    val builder = new ProbabilityDensityBuilder[Int]()
    sortedQueues.zipWithIndex.foreach { case (queue, index) => builder.add(index, weights(queue)) }
    builder.build()
  }
  def nextBatchWithLock(n: Int, lockTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Seq[SQSMessage[T]]] = {
    val offset = offsetDensity.sample(Random.nextDouble()).get
    // not using foldLeftWhile on purpose: it doesn't return the last accumulator, which may lock tasks without returning them
    FutureHelpers.foldLeftUntil(sortedQueues.cycle(offset))(Seq.empty[SQSMessage[T]]) {
      case (tasks, nextQueue) =>
        val numberOfMissingTasks = n - tasks.length
        if (numberOfMissingTasks <= 0) Future.successful((tasks, true))
        else {
          log.info(s"Pulling up to $numberOfMissingTasks tasks from $nextQueue")
          nextQueue.nextBatchWithLock(numberOfMissingTasks, lockTimeout).imap { moreTasks =>
            log.info(s"Pulled ${moreTasks.size}/$numberOfMissingTasks tasks from $nextQueue")
            (tasks ++ moreTasks, false)
          }
        }
    }
  }
}
