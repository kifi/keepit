package com.keepit.rover.manager

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Try, Failure, Success }

object RoverFetchSchedulingActor {
  val maxBatchSize = 15 // low to balance producer / consumer behavior *on leader* (SQS send / receive), increase if we don't care about leader as a consumer.
  val maxQueuedFor = 12 hours

  sealed trait RoverFetchSchedulingActorMessage
  case object ScheduleFetchTasks extends RoverFetchSchedulingActorMessage
  case class DoneScheduling(mayHaveMore: Boolean) extends RoverFetchSchedulingActorMessage
  case object CancelScheduling extends RoverFetchSchedulingActorMessage
}

class RoverFetchSchedulingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    airbrake: AirbrakeNotifier,
    firstTimeQueue: FetchTaskQueue.FirstTime,
    newVersionQueue: FetchTaskQueue.NewVersion,
    refreshQueue: FetchTaskQueue.Refresh,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import RoverFetchSchedulingActor._

  private[this] var schedulingFetchTasks = false

  def receive = {
    case fetchSchedulingMessage: RoverFetchSchedulingActorMessage =>
      fetchSchedulingMessage match {
        case ScheduleFetchTasks => if (!schedulingFetchTasks) { startScheduling() }
        case DoneScheduling(mayHaveMore) => if (mayHaveMore) startScheduling() else endScheduling()
        case CancelScheduling => endScheduling()
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def endScheduling(): Unit = {
    schedulingFetchTasks = false
  }

  private def startScheduling(): Unit = {
    schedulingFetchTasks = true
    val ripeArticleInfos = db.readWrite { implicit session =>
      articleInfoRepo.getRipeForFetching(maxBatchSize, maxQueuedFor)
    }

    val queuedTaskCountFutures = ripeArticleInfos.groupBy(getRelevantQueue).map {
      case (queue, articleInfos) =>
        val tasks = articleInfos.map { articleInfo => FetchTask(articleInfo.id.get) }
        add(tasks, queue)
    }

    Future.sequence(queuedTaskCountFutures).imap(_.sum).onComplete {
      case Success(queuedTaskCount) => {
        log.info(s"Added $queuedTaskCount article fetch tasks.")
        self ! DoneScheduling(mayHaveMore = ripeArticleInfos.nonEmpty)
      }
      case Failure(error) => {
        log.error(s"Failed to add article fetch tasks.", error)
        self ! CancelScheduling
      }
    }
  }

  private def getRelevantQueue(articleInfo: RoverArticleInfo): FetchTaskQueue = articleInfo.latestVersion match {
    case None => firstTimeQueue
    case Some(version) => if (version.major < articleInfo.articleKind.version) newVersionQueue else refreshQueue
  }

  private def add(tasks: Seq[FetchTask], queue: FetchTaskQueue): Future[Int] = {
    val maybeQueuedTaskFutures: Seq[Future[Try[FetchTask]]] = tasks.map { task =>
      val futureMessage = queue.queue.send(task)
      futureMessage.imap { _ => Success(task) } recover {
        case error: Throwable =>
          log.error(s"Failed to add $task to queue $queue", error)
          Failure(error)
      }
    }
    Future.sequence(maybeQueuedTaskFutures).map { maybeQueuedTasks =>
      val queuedTasks = maybeQueuedTasks.collect { case Success(task) => task }
      val queuedTaskCount = queuedTasks.length
      val failureCount = tasks.length - queuedTaskCount
      if (failureCount > 0) { log.error(s"Failed to add $failureCount tasks to $queue") }
      if (queuedTaskCount > 0) {
        db.readWrite { implicit session =>
          articleInfoRepo.markAsQueued(queuedTasks.map(_.id): _*)
        }
        log.info(s"Added $queuedTaskCount tasks to $queue")
      }
      queuedTaskCount
    }
  }
}
