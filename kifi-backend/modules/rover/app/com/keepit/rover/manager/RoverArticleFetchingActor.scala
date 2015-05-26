package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.ArticleCommander
import com.keepit.rover.image.ImageCommander
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }

object RoverArticleFetchingActor {
  val lockTimeOut = 10 minutes
}

class RoverArticleFetchingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticFetchTaskQueue,
    articleCommander: ArticleCommander,
    airbrake: AirbrakeNotifier,
    imageProcessingCommander: ImageCommander,
    instanceInfo: AmazonInstanceInfo,
    implicit val executionContext: ExecutionContext) extends ConcurrentTaskProcessingActor[SQSMessage[FetchTask]](airbrake) {

  private val concurrencyFactor = 25
  protected val maxConcurrentTasks: Int = 1 + instanceInfo.instantTypeInfo.cores * concurrencyFactor
  protected val minConcurrentTasks: Int = 1 + maxConcurrentTasks / 2

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
    shouldFetch(articleInfo) match {
      case false => Future.successful(())
      case true => {
        articleCommander.fetchAndPersist(articleInfo) imap { fetched =>
          if (fetched.isDefined && articleInfo.lastImageProcessingVersion.isEmpty) {
            SafeFuture { imageProcessingCommander.processArticleImagesAsap(articleInfo.id.toSet) }
          }
          ()
        }
      }
    }
  } andThen { case _ => task.consume() } // failures are handled and persisted to the database, always consume

  private def shouldFetch(info: RoverArticleInfo) = info.isActive && info.lastFetchingAt.isDefined
}
