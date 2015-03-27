package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.rover.article.ArticleFetcherProvider
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleFetchingActor {
  val minConcurrentFetchTasks: Int = 5
  val maxConcurrentFetchTasks: Int = 10
  val lockTimeOut = 5 minutes
  sealed trait RoverFetchingActorMessage
  case object Close extends RoverFetchingActorMessage
  case object StartPullingTasks extends RoverFetchingActorMessage
  case class CancelPulling(limit: Int) extends RoverFetchingActorMessage
  case class Pulled(tasks: Seq[SQSMessage[FetchTask]], limit: Int) extends RoverFetchingActorMessage
  case class Fetched(task: SQSMessage[FetchTask]) extends RoverFetchingActorMessage
}

class RoverArticleFetchingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticFetchTaskQueue,
    airbrake: AirbrakeNotifier,
    articleFetcher: ArticleFetcherProvider,
    articleStore: RoverArticleStore,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  import RoverArticleFetchingActor._

  private[this] var closing = false
  private[this] var pulling = 0
  private[this] var fetching = Set.empty[SQSMessage[FetchTask]]

  private def concurrentFetchTasks = pulling + fetching.size

  def receive = {
    case fetchingMessage: RoverFetchingActorMessage => {
      fetchingMessage match {
        case StartPullingTasks => startPulling()
        case CancelPulling(limit) => endPulling(limit)
        case Pulled(tasks, limit) => {
          endPulling(limit)
          processTasks(tasks)
        }
        case Fetched(task) => endFetching(task)
        case Close => close()
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def startPulling(): Unit = if (!closing) {
    val limit = maxConcurrentFetchTasks - concurrentFetchTasks
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

  private def processTasks(tasks: Seq[SQSMessage[FetchTask]]): Unit = {
    if (!closing && tasks.nonEmpty) {
      val articleInfosById = db.readOnlyMaster { implicit session =>
        articleInfoRepo.getAll(tasks.map(_.body.id).toSet)
      }
      val moreFetching = tasks.filter(task => startFetching(task, articleInfosById(task.body.id)))
      fetching ++= moreFetching
    }
  }

  private def startFetching(task: SQSMessage[FetchTask], articleInfo: RoverArticleInfo): Boolean = {
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
    if (concurrentFetchTasks < minConcurrentFetchTasks) {
      startPulling()
    }
  }

  private def close(): Unit = {
    closing = true
  }
}
