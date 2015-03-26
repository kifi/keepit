package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.rover.article.ArticleFetcherProvider
import com.keepit.rover.model.{ ArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverFetchingActor {
  val minConcurrentFetchTasks: Int = 5
  val maxConcurrentFetchTasks: Int = 10
  val lockTimeOut = 5 minutes
  sealed trait RoverFetchingActorMessage
  case object StartPulling extends RoverFetchingActorMessage
  case class Pulled(tasks: Seq[SQSMessage[FetchTask]], limit: Int) extends RoverFetchingActorMessage
  case class Fetched(task: SQSMessage[FetchTask]) extends RoverFetchingActorMessage
  case class CancelPulling(limit: Int) extends RoverFetchingActorMessage
}

class RoverFetchingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticFetchTaskQueue,
    airbrake: AirbrakeNotifier,
    articleFetcher: ArticleFetcherProvider,
    articleStore: RoverArticleStore,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import RoverFetchingActor._

  private var pulling = 0
  private var fetching = Set.empty[SQSMessage[FetchTask]] // todo(Léo): a count might be enough here, but this might be useful to release SQS tasks in panic mode

  def receive = {
    case fetchingMessage: RoverFetchingActorMessage => {
      fetchingMessage match {
        case StartPulling => startPulling()
        case CancelPulling(limit) => endPulling(limit)
        case Pulled(tasks, limit) => processTasks(tasks, limit)
        case Fetched(task) => endFetching(task)
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startPulling(): Unit = {
    val limit = maxConcurrentFetchTasks - pulling - fetching.size
    if (limit > 0) {
      pulling += limit
      taskQueue.nextBatchWithLock(limit, lockTimeOut).onComplete {
        case Success(tasks) => {
          log.info(s"Pulled ${tasks.length}/$limit fetch tasks.")
          self ! Pulled(tasks, limit)
        }

        case Failure(error) => {
          log.error("Failed to pull fetch tasks.", error)
          self ! CancelPulling(limit)
        }
      }
    }
  }

  private def endPulling(limit: Int): Unit = {
    pulling -= limit
  }

  private def processTasks(tasks: Seq[SQSMessage[FetchTask]], limit: Int): Unit = {
    endPulling(limit)
    if (tasks.nonEmpty) {
      val articleInfosById = db.readOnlyMaster { implicit session =>
        articleInfoRepo.getAll(tasks.map(_.body.id).toSet)
      }
      val moreFetching = tasks.filter(task => startFetching(task, articleInfosById(task.body.id)))
      fetching ++= moreFetching
    }
  }

  private def startFetching(task: SQSMessage[FetchTask], articleInfo: ArticleInfo): Boolean = {
    if (articleInfo.id != Some(task.body.id)) { throw new IllegalArgumentException(s"ArticleInfo with id ${articleInfo.id} does not match $task") }

    articleInfo.shouldFetch tap {
      case false => task.consume()
      case true => {
        articleFetcher.fetch(articleInfo.getFetchRequest).flatMap {
          case None => Future.successful(None)
          case Some(article) => articleStore.add(articleInfo.uriId, articleInfo.latestVersion, article)(articleInfo.articleKind).imap(key => Some(key.version))
        }.onComplete {
          case fetched =>
            db.readWrite { implicit session =>
              articleInfoRepo.updateAfterFetch(articleInfo.uriId, articleInfo.articleKind, fetched)
            }
            task.consume()
            self ! Fetched(task)
        }
      }
    }
  }

  private def endFetching(task: SQSMessage[FetchTask]): Unit = {
    fetching -= task
    if (fetching.size < minConcurrentFetchTasks) {
      startPulling()
    }
  }
}
