package com.keepit.rover.manager

import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.concurrent.ExecutionContext

import scala.concurrent.{ Future }
import scala.util.{ Failure, Success }

object TaskProcessingActor {
  trait TaskProcessingMessage
  case object Close extends TaskProcessingMessage
  case object StartPullingTasks extends TaskProcessingMessage

  case class UnknownTaskStatusException[T](task: T) extends Exception(s"Unknown task status: $task")
}

abstract class TaskProcessingActor[T](airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  protected val minConcurrentTasks: Int
  protected val maxConcurrentTasks: Int

  protected def pullTasks(limit: Int): Future[Seq[T]]
  protected def processTasks(tasks: Seq[T]): Map[T, Future[Unit]]

  import TaskProcessingActor._

  private[this] case class Pulled(tasks: Seq[T], limit: Int) extends TaskProcessingMessage
  private[this] case class Processed(task: T) extends TaskProcessingMessage
  private[this] case class CancelPulling(limit: Int) extends TaskProcessingMessage

  private[this] var closing = false
  private[this] var pulling = 0
  private[this] var processing = Set.empty[T]

  private def concurrentFetchTasks = pulling + processing.size

  def receive = {
    case taskProcessingMessage: TaskProcessingMessage => {
      taskProcessingMessage match {
        case StartPullingTasks => startPulling()
        case CancelPulling(limit) => endPulling(limit)
        case Pulled(tasks, limit) => {
          endPulling(limit)
          startProcessing(tasks)
        }
        case Processed(task) => endProcessing(task)
        case Close => close()
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startPulling(): Unit = if (!closing) {
    val limit = maxConcurrentTasks - concurrentFetchTasks
    if (limit > 0) {
      log.info(s"Pulling up to $limit tasks.")
      pulling += limit

      val pulledTasks = try {
        pullTasks(limit)
      } catch {
        case error: Exception =>
          Future.failed(error)
      }

      pulledTasks.onComplete {
        case Success(tasks) => {
          log.info(s"Pulled ${tasks.length}/$limit tasks.")
          self ! Pulled(tasks, limit)
        }

        case Failure(error) => {
          log.error("Failed to pull tasks.", error)
          self ! CancelPulling(limit)
        }
      }(ExecutionContext.immediate)
    }
  }

  private def endPulling(limit: Int): Unit = {
    pulling -= limit
  }

  private def startProcessing(tasks: Seq[T]): Unit = {
    if (!closing && tasks.nonEmpty) {
      log.info(s"Processing ${tasks.length} tasks...")
      processing ++= tasks
      val processedTasks = try {
        processTasks(tasks)
      } catch {
        case error: Exception =>
          Map.empty[T, Future[Unit]] withDefaultValue Future.failed(error)
      }
      tasks.foreach { task =>
        val processedTask = processedTasks.getOrElse(task, Future.failed(UnknownTaskStatusException(task)))
        processedTask.onComplete { result =>
          result match {
            case Failure(error) => log.error(s"Failed processing $task", error)
            case Success(()) => log.info(s"Processed $task")
          }
          self ! Processed(task)
        }(ExecutionContext.immediate)
      }
    }
  }

  private def endProcessing(task: T): Unit = {
    processing -= task
    if (concurrentFetchTasks < minConcurrentTasks) {
      startPulling()
    }
  }

  private def close(): Unit = {
    closing = true
    log.info(s"Closed $this.")
  }
}
