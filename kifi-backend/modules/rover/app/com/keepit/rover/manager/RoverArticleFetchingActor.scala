package com.keepit.rover.manager

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.article.ArticleFetcherProvider
import com.keepit.rover.commanders.{ FetchCommander, ImageProcessingCommander }
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo }
import com.keepit.rover.store.RoverArticleStore
import com.kifi.franz.SQSMessage
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }

object RoverArticleFetchingActor {
  val lockTimeOut = 10 minutes
  val minConcurrentTasks: Int = 150
  val maxConcurrentTasks: Int = 200
}

class RoverArticleFetchingActor @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    taskQueue: ProbabilisticFetchTaskQueue,
    fetchCommander: FetchCommander,
    airbrake: AirbrakeNotifier,
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
    articleInfo.shouldFetch match {
      case false => Future.successful(())
      case true => fetchCommander.fetchAndPersist(articleInfo) imap { fetched =>
        if (fetched.isDefined) {
          SafeFuture { imageProcessingCommander.processArticleImagesAsap(articleInfo.id.toSet) }
        }
        ()
      }
    }
  } andThen { case _ => task.consume() } // failures are handled and persisted to the database, always consume
}
