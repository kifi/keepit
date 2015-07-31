package com.keepit.rover.manager

import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.logging.Logging
import com.kifi.franz.SQSQueue

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import com.keepit.common.core._

trait TaskQueue[T] { self: Logging =>

  protected val concurrentlyQueuedTasks = 15 // low to balance producer / consumer behavior *on leader* (SQS send / receive).
  private val throttle = new ReactiveLock(concurrentlyQueuedTasks)

  def queue: SQSQueue[T]
  def add(tasks: Seq[T])(implicit ec: ExecutionContext): Future[Map[T, Try[Unit]]] = {
    val maybeQueuedTaskFutures: Seq[Future[(T, Try[Unit])]] = tasks.map { task =>
      val futureMessage = throttle.withLockFuture(queue.send(task))
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
      if (failureCount > 0) { log.error(s"Failed to add $failureCount tasks to $this") }
      maybeQueuedTasks.toMap
    }
  }
}
