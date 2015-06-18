package com.keepit.rover.manager

import akka.actor.Actor

import scala.concurrent.{ Future }
import scala.util.{ Try, Failure, Success }

object ConcurrentTaskProcessingActor {
  trait TaskProcessingActorMessage
  case object Close extends TaskProcessingActorMessage
  case object IfYouCouldJustGoAhead extends TaskProcessingActorMessage

  case class UnknownTaskStatusException[T](task: T) extends Exception(s"Unknown task status: $task")
  case class UnsupportedActorMessageException(any: Any) extends IllegalStateException(if (any != null) any.toString else "Message is NULL")

}

trait ConcurrentTaskProcessingActor[T] { _: Actor =>

  protected val logger: org.slf4j.Logger

  protected val minConcurrentTasks: Int
  protected val maxConcurrentTasks: Int

  protected def pullTasks(limit: Int): Future[Seq[T]]
  protected def processTasks(tasks: Seq[T]): Map[T, Future[Unit]]

  protected val immediately = new scala.concurrent.ExecutionContext {
    def execute(runnable: Runnable): Unit = { runnable.run() }
    def reportFailure(t: Throwable): Unit = { }
    override def prepare(): scala.concurrent.ExecutionContext = this
  }

  import ConcurrentTaskProcessingActor._

  private[this] case class Pulled(result: Try[Seq[T]], limit: Int) extends TaskProcessingActorMessage
  private[this] case class Processed(task: T, result: Try[Unit]) extends TaskProcessingActorMessage

  private[this] var closing = false
  private[this] var pulling = 0
  private[this] var processing = Set.empty[T]

  private def concurrentFetchTasks = pulling + processing.size

  def receive: akka.actor.Actor.Receive = {
    case taskProcessingMessage: TaskProcessingActorMessage => {
      taskProcessingMessage match {
        case IfYouCouldJustGoAhead => startPulling()
        case Pulled(result, limit) => endPulling(result, limit)
        case Processed(task, result) => endProcessing(task)
        case Close => close()
      }
    }
    case m => throw new UnsupportedActorMessageException(m)
  }

  private def startPulling(): Unit = if (!closing) {
    val limit = maxConcurrentTasks - concurrentFetchTasks
    if (limit > 0) {
      logger.info(s"Pulling up to $limit tasks.")
      pulling += limit

      val pulledTasks = try {
        pullTasks(limit)
      } catch {
        case error: Exception => Future.failed(error)
      }

      pulledTasks.onComplete { result =>
        self ! Pulled(result, limit)
      }(immediately)
    }
  }

  private def endPulling(pulled: Try[Seq[T]], limit: Int): Unit = {
    pulling -= limit
    pulled match {
      case Success(tasks) => {
        logger.info(s"Pulled ${tasks.length} tasks.")
        startProcessing(tasks)
      }
      case Failure(error) => {
        logger.error("Failed to pull tasks.", error)
      }
    }
  }

  private def startProcessing(tasks: Seq[T]): Unit = {
    if (!closing && tasks.nonEmpty) {
      logger.info(s"Processing ${tasks.length} tasks...")
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
        }(immediately)
      }
    }
  }

  private def endProcessing(task: T): Unit = {
    processing -= task
    logger.info(s"Processed $task.")
    if (concurrentFetchTasks < minConcurrentTasks) {
      startPulling()
    }
  }

  private def close(): Unit = {
    closing = true
    logger.info(s"Closed $this.")
  }
}

trait BatchProcessingActor[T] extends ConcurrentTaskProcessingActor[Seq[T]] { _: Actor =>
  final protected val minConcurrentTasks: Int = 1
  final protected val maxConcurrentTasks: Int = 1

  final protected def pullTasks(limit: Int): Future[Seq[Seq[T]]] = {
    if (limit == 1) nextBatch.map(Seq(_).filter(_.nonEmpty))(immediately) else Future.successful(Seq.empty)
  }
  final protected def processTasks(tasks: Seq[Seq[T]]): Map[Seq[T], Future[Unit]] = {
    tasks.map { batch => batch -> processBatch(batch) }.toMap
  }

  protected def nextBatch: Future[Seq[T]]
  protected def processBatch(batch: Seq[T]): Future[Unit]
}