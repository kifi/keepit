package com.keepit.common.queue

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.math.ProbabilityDensityBuilder
import com.keepit.common.CollectionHelpers._
import com.kifi.franz.{ SQSMessage, SQSQueue }

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

class ProbabilisticMultiQueue[T](weights: Map[SQSQueue[T], Double]) {
  private val sortedQueues = weights.keySet.toSeq.sortBy(-weights(_))
  private val offsetDensity = {
    val builder = new ProbabilityDensityBuilder[Int]()
    sortedQueues.zipWithIndex.foreach { case (queue, index) => builder.add(index, weights(queue)) }
    builder.build()
  }
  def nextBatchWithLock(n: Int, lockTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Seq[SQSMessage[T]]] = {
    val offset = offsetDensity.sample(Random.nextDouble()).get
    // not using foldLeftWhile on purpose: it doesn't return the last accumulator, which may lock tasks without returning them
    FutureHelpers.foldLeft(sortedQueues.cycle(offset))(Seq.empty[SQSMessage[T]]) {
      case (tasks, nextQueue) =>
        val numberOfMissingTasks = n - tasks.length
        if (numberOfMissingTasks <= 0) Future.successful(tasks)
        else nextQueue.nextBatchWithLock(numberOfMissingTasks, lockTimeout).map { moreTasks => tasks ++ moreTasks }
    }
  }
}
