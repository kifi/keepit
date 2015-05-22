package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.fetcher.ArticleFetcherProvider
import com.keepit.rover.article.{ Article, EmbedlyArticle }
import com.keepit.rover.image.ImageCommander
import com.keepit.rover.model.{ ArticleKey, RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object RoverArticleImageProcessingActor {
  val lockTimeOut = 10 minutes
}

class RoverArticleImageProcessingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticArticleImageProcessingTaskQueue,
    airbrake: AirbrakeNotifier,
    articleFetcher: ArticleFetcherProvider,
    articleStore: RoverArticleStore,
    imageProcessingCommander: ImageCommander,
    instanceInfo: AmazonInstanceInfo,
    implicit val executionContext: ExecutionContext) extends ConcurrentTaskProcessingActor[SQSMessage[ArticleImageProcessingTask]](airbrake) {

  private val concurrencyFactor = 1
  protected val maxConcurrentTasks: Int = 1 + instanceInfo.instantTypeInfo.cores * concurrencyFactor
  protected val minConcurrentTasks: Int = 1 + maxConcurrentTasks / 2

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
    shouldProcessLatestArticleImages(articleInfo) match {
      case false => Future.successful(())
      case true => imageProcessingCommander.processLatestArticleImages(articleInfo).imap(_ => ())
    }
  } andThen { // relying on SQS for retries
    case Success(()) => task.consume()
    case Failure(error) => log.error(s"Failed to process $task", error)
  }

  private def shouldProcessLatestArticleImages(info: RoverArticleInfo) = info.isActive && info.lastImageProcessingAt.isDefined

}
