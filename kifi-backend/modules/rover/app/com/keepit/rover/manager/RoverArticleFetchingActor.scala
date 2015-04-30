package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.ArticleFetcherProvider
import com.keepit.rover.commanders.ImageProcessingCommander
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }

object RoverArticleFetchingActor {
  val lockTimeOut = 10 minutes
  val minConcurrentTasks: Int = 300
  val maxConcurrentTasks: Int = 400
}

class RoverArticleFetchingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticFetchTaskQueue,
    airbrake: AirbrakeNotifier,
    articleFetcher: ArticleFetcherProvider,
    articleStore: RoverArticleStore,
    imageProcessingCommander: ImageProcessingCommander,
    implicit val executionContext: ExecutionContext) extends ConcurrentTaskProcessingActor[SQSMessage[FetchTask]](airbrake) {

  protected val minConcurrentTasks: Int = RoverArticleFetchingActor.minConcurrentTasks
  protected val maxConcurrentTasks: Int = RoverArticleFetchingActor.maxConcurrentTasks

  protected def pullTasks(limit: Int): Future[Seq[SQSMessage[FetchTask]]] = {
    taskQueue.nextBatchWithLock(limit, RoverArticleFetchingActor.lockTimeOut)
  }

  protected def processTasks(tasks: Seq[SQSMessage[FetchTask]]): Map[SQSMessage[FetchTask], Future[Unit]] = {
    val articleInfosById = db.readOnlyMaster { implicit session =>
      articleInfoRepo.getAll(tasks.map(_.body.id).toSet)
    }
    tasks.map { task =>
      val articleInfo = articleInfosById(task.body.id)
      task -> process(task, articleInfo)
    }.toMap
  }

  private def process(task: SQSMessage[FetchTask], articleInfo: RoverArticleInfo): Future[Unit] = {
    fetch(task, articleInfo) andThen { case _ => task.consume() } // failures are handled and persisted to the database, always consume
  }

  private def fetch(task: SQSMessage[FetchTask], articleInfo: RoverArticleInfo): Future[Unit] = {
    if (articleInfo.id != Some(task.body.id)) { throw new IllegalArgumentException(s"ArticleInfo with id ${articleInfo.id} does not match $task") }

    articleInfo.shouldFetch match {
      case false => Future.successful(())
      case true => {
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
        } andThen {
          case fetched =>
            fetched recover {
              case error: Exception =>
                log.error(s"Failed to fetch ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}", error)
            }
            db.readWrite { implicit session =>
              articleInfoRepo.updateAfterFetch(articleInfo.uriId, articleInfo.articleKind, fetched)
            }

          // todo(Léo): Turn on.
          /*if (fetched.toOption.flatten.isDefined) SafeFuture {
              imageProcessingCommander.processArticleImagesAsap(articleInfo.id.toSet)
            }*/
        }
      } imap { _ => () }
    }
  }
}
