package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.{ Article, EmbedlyArticle, ArticleFetcherProvider }
import com.keepit.rover.commanders.ImageProcessingCommander
import com.keepit.rover.model.{ ArticleKey, RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleImageProcessingActor {
  val lockTimeOut = 10 minutes
  val minConcurrentTasks: Int = 50
  val maxConcurrentTasks: Int = 150
}

class RoverArticleImageProcessingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticArticleImageProcessingTaskQueue,
    airbrake: AirbrakeNotifier,
    articleFetcher: ArticleFetcherProvider,
    articleStore: RoverArticleStore,
    imageProcessingCommander: ImageProcessingCommander,
    implicit val executionContext: ExecutionContext) extends ConcurrentTaskProcessingActor[SQSMessage[ArticleImageProcessingTask]](airbrake) {

  protected val minConcurrentTasks: Int = RoverArticleImageProcessingActor.minConcurrentTasks
  protected val maxConcurrentTasks: Int = RoverArticleImageProcessingActor.maxConcurrentTasks

  protected def pullTasks(limit: Int): Future[Seq[SQSMessage[ArticleImageProcessingTask]]] = {
    taskQueue.nextBatchWithLock(limit, RoverArticleImageProcessingActor.lockTimeOut)
  }

  protected def processTasks(tasks: Seq[SQSMessage[ArticleImageProcessingTask]]): Map[SQSMessage[ArticleImageProcessingTask], Future[Unit]] = {
    val articleInfosById = db.readOnlyMaster { implicit session =>
      articleInfoRepo.getAll(tasks.map(_.body.id).toSet)
    }
    tasks.map { task =>
      val articleInfo = articleInfosById(task.body.id)
      task -> process(task, articleInfo)
    }.toMap
  }

  private def process(task: SQSMessage[ArticleImageProcessingTask], articleInfo: RoverArticleInfo): Future[Unit] = {
    process(articleInfo) andThen { // relying on SQS for retries
      case Success(()) => task.consume()
      case Failure(error) => log.error(s"Failed to process $task", error)
    }
  }

  private def process(articleInfo: RoverArticleInfo): Future[Unit] = {
    articleInfo.shouldProcessLatestArticleImages match {
      case false => Future.successful(())
      case true => articleInfo.getLatestKey match {
        case None => Future.successful(())
        case Some(latestArticleKey) => {
          processArticleImages(latestArticleKey).imap { _ =>
            db.readWrite(attempts = 3) { implicit session =>
              articleInfoRepo.updateAfterImageProcessing(latestArticleKey.uriId, latestArticleKey.kind, latestArticleKey.version)
            }
          }
        }
      }
    }
  }

  private def processArticleImages(key: ArticleKey[_ <: Article]): Future[Unit] = {
    getRemoteImageUrls(key).flatMap { remoteImageUrls =>
      FutureHelpers.sequentialExec(remoteImageUrls) { remoteImageUrl =>
        imageProcessingCommander.processRemoteArticleImage(key.uriId, key.kind, key.version, remoteImageUrl)
      }
    }
  }

  private def getRemoteImageUrls(key: ArticleKey[_ <: Article]): Future[Set[String]] = {
    articleStore.get(key).map {
      case Some(embedlyArticle: EmbedlyArticle) => embedlyArticle.content.images.map(_.url).headOption.toSet // only take the first image provided by Embedly
      case _ => Set.empty[String]
    }
  }
}
