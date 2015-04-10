package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.rover.article.ArticleFetcherProvider
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.keepit.scraper.ShortenedUrls
import com.kifi.franz.SQSMessage
import scala.collection.mutable.{ Map => MutableMap }
import scala.collection.Map
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleFetchingActor {
  val minConcurrentFetchTasks: Int = 300
  val maxConcurrentFetchTasks: Int = 400
  val lockTimeOut = 10 minutes
  val domainWideThrottlingWindow = 1 minute
  val domainWideThrottlingLimit = 5
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
      log.info(s"Pulling up to $limit fetch tasks.")
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
      val (articleInfosById, recentFetchesByDomain) = db.readOnlyMaster { implicit session =>
        val articleInfosById = articleInfoRepo.getAll(tasks.map(_.body.id).toSet)
        val recentFetchesByDomain = {
          val uniqueDomains = articleInfosById.values.flatMap(_.domain).toSet
          MutableMap().withDefaultValue(0) ++= articleInfoRepo.countRecentFetchesByDomain(uniqueDomains, domainWideThrottlingWindow)
        }
        (articleInfosById, recentFetchesByDomain)
      }

      val moreFetching = tasks.filter { task =>
        val articleInfo = articleInfosById(task.body.id)
        if (!articleInfo.shouldFetch) {
          task.consume()
          false
        } else {
          if (shouldThrottle(recentFetchesByDomain)(articleInfo)) {
            false // task will show up in the queue again within lockTimeOut
          } else {
            startFetching(task, articleInfo)
            articleInfo.domain.foreach(recentFetchesByDomain(_) += 1)
            true
          }
        }
      }

      log.info(s"Started fetching ${moreFetching.length} articles.")
      fetching ++= moreFetching
    }
  }

  private def shouldThrottle(recentFetchesByDomain: Map[String, Int])(info: RoverArticleInfo): Boolean = {
    info.domain.exists(domain => !ShortenedUrls.domains.contains(domain) && recentFetchesByDomain.get(domain).exists(_ >= domainWideThrottlingLimit))
  }

  private def startFetching(task: SQSMessage[FetchTask], articleInfo: RoverArticleInfo): Unit = {
    if (articleInfo.id != Some(task.body.id)) { throw new IllegalArgumentException(s"ArticleInfo with id ${articleInfo.id} does not match $task") }
    log.info(s"Fetching ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}")
    articleFetcher.fetch(articleInfo.getFetchRequest).flatMap {
      case None => {
        log.info(s"No ${articleInfo.articleKind} fetched for uri ${articleInfo.uriId}: ${articleInfo.url}")
        Future.successful(None)
      }
      case Some(article) => {
        log.info(s"Persisting latest ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}")
        articleStore.add(articleInfo.uriId, articleInfo.latestVersion, article)(articleInfo.articleKind).imap { key =>
          log.info(s"Persisted latest ${articleInfo.articleKind} with version ${key.version} for uri ${articleInfo.uriId}: ${articleInfo.url}")
          Some(key.version)
        }
      }
    } recoverWith {
      case error =>
        log.error(s"Failed to fetch ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}", error)
        Future.failed(error)
    } onComplete {
      case fetched =>
        db.readWrite { implicit session =>
          articleInfoRepo.updateAfterFetch(articleInfo.uriId, articleInfo.articleKind, fetched)
        }
        task.consume()
        self ! Fetched(task)
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
