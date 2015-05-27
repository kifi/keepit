package com.keepit.rover.manager

import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.concurrent.ExecutionContext

import scala.concurrent.{ Future }
import scala.util.{ Try, Failure, Success }
import com.keepit.common.core._

object ConcurrentTaskProcessingActor {
  trait TaskProcessingActorMessage
  case object Close extends TaskProcessingActorMessage
  case object IfYouCouldJustGoAhead extends TaskProcessingActorMessage

  case class UnknownTaskStatusException[T](task: T) extends Exception(s"Unknown task status: $task")
}

abstract class ConcurrentTaskProcessingActor[T](airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  protected val minConcurrentTasks: Int
  protected val maxConcurrentTasks: Int

  protected def pullTasks(limit: Int): Future[Seq[T]]
  protected def processTasks(tasks: Seq[T]): Map[T, Future[Unit]]

  import ConcurrentTaskProcessingActor._

  private[this] case class Pulled(result: Try[Seq[T]], limit: Int) extends TaskProcessingActorMessage
  private[this] case class Processed(task: T, result: Try[Unit]) extends TaskProcessingActorMessage

  private[this] var closing = false
  private[this] var pulling = 0
  private[this] var processing = Set.empty[T]

  private def concurrentFetchTasks = pulling + processing.size

  def receive = {
    case taskProcessingMessage: TaskProcessingActorMessage => {
      taskProcessingMessage match {
        case IfYouCouldJustGoAhead => startPulling()
        case Pulled(result, limit) => endPulling(result, limit)
        case Processed(task, result) => endProcessing(task)
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
        case error: Exception => Future.failed(error)
      }

      pulledTasks.onComplete { result =>
        self ! Pulled(result, limit)
      }(ExecutionContext.immediate)
    }
  }

  private def endPulling(pulled: Try[Seq[T]], limit: Int): Unit = {
    pulling -= limit
    pulled match {
      case Success(tasks) => {
        log.info(s"Pulled ${tasks.length} tasks.")
        startProcessing(tasks)
      }
      case Failure(error) => {
        log.error("Failed to pull tasks.", error)
      }
    }
  }

  private def startProcessing(tasks: Seq[T]): Unit = {
    if (!closing && tasks.nonEmpty) {
      log.info(s"Processing ${tasks.length} tasks...")
      processing ++= tasks

      val processedTasks = try {
        processTasks(tasks)
      } catch {
        case error: Exception => Map.empty[T, Future[Unit]] withDefaultValue Future.failed(error)
      }

      tasks.foreach { task =>
        val processedTask = processedTasks.getOrElse(task, Future.failed(UnknownTaskStatusException(task)))
        processedTask.onComplete { result =>
          self ! Processed(task, result)
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

abstract class BatchProcessingActor[T](airbrake: AirbrakeNotifier) extends ConcurrentTaskProcessingActor[Seq[T]](airbrake) {
  final protected val minConcurrentTasks: Int = 1
  final protected val maxConcurrentTasks: Int = 1

  final protected def pullTasks(limit: Int): Future[Seq[Seq[T]]] = {
    if (limit == 1) nextBatch.imap(Seq(_).filter(_.nonEmpty)) else Future.successful(Seq.empty)
  }
  final protected def processTasks(tasks: Seq[Seq[T]]): Map[Seq[T], Future[Unit]] = {
    tasks.map { batch => batch -> processBatch(batch) }.toMap
  }

  protected def nextBatch: Future[Seq[T]]
  protected def processBatch(batch: Seq[T]): Future[Unit]
}