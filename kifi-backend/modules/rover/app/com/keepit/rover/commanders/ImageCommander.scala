package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.manager.{ ArticleImageProcessingTask, ArticleImageProcessingTaskQueue }
import com.keepit.rover.model._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Try }
import com.keepit.common.core._

object ArticleImageConfiguration {
  val scaleSizes = ScaledImageSize.allSizes.toSet
  val cropSizes = Set.empty[CroppedImageSize]
  val imagePathPrefix = "i"
}

case class ImageStoreFailureException(failure: ImageStoreFailure) extends Exception(failure.reason, failure.cause.orNull)

@Singleton
class ImageCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    articleImageRepo: ArticleImageRepo,
    fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow,
    imageFetcher: RoverImageFetcher,
    private implicit val executionContext: ExecutionContext) extends Logging {

  def add(tasks: Seq[ArticleImageProcessingTask], queue: ArticleImageProcessingTaskQueue): Future[Map[ArticleImageProcessingTask, Try[Unit]]] = {
    db.readWrite { implicit session =>
      articleInfoRepo.markAsImageProcessing(tasks.map(_.id): _*)
    }
    queue.add(tasks).map { maybeQueuedTasks =>
      val failedTasks = maybeQueuedTasks.collect { case (task, Failure(_)) => task }.toSeq
      if (failedTasks.nonEmpty) {
        db.readWrite { implicit session =>
          articleInfoRepo.unmarkAsImageProcessing(failedTasks.map(_.id): _*)
        }
      }
      maybeQueuedTasks
    }
  }

  def getRipeForImageProcessing(limit: Int, fetchedForMoreThan: Duration, imageProcessingForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForImageProcessing(limit, fetchedForMoreThan, imageProcessingForMoreThan)
    }
  }

  def processArticleImagesAsap(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
    val tasks = ids.map(ArticleImageProcessingTask(_)).toSeq
    add(tasks, fastFollowQueue).imap { _.map { case (task, result) => (task.id -> result) } }
  }

  def processRemoteArticleImage(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], version: ArticleVersion, remoteImageUrl: String): Future[Unit] = {
    import ArticleImageConfiguration._
    val result = imageFetcher.fetchAndStoreRemoteImage(remoteImageUrl, ImageSource.RoverArticle(kind), imagePathPrefix, scaleSizes, cropSizes).map {
      case Right(sourceImageHash) => {
        try {
          db.readWrite(attempts = 3) { implicit session =>
            articleImageRepo.intern(uriId, kind, sourceImageHash, remoteImageUrl, version)
          }
          Right(())
        } catch {
          case articleImageError: Exception =>
            log.error(s"Failed to update ArticleImageRepo (uriId: $uriId, $kind, $version) after fetching image from $remoteImageUrl: $articleImageError")
            Left(ImageProcessState.DbPersistFailed(articleImageError))
        }
      }
      case Left(imageProcessingError) => Left(imageProcessingError)
    }

    result.imap {
      case Right(()) => ()
      case Left(dbFailed: ImageProcessState.DbPersistFailed) => throw ImageStoreFailureException(dbFailed)
      case Left(uploadFailed: ImageProcessState.CDNUploadFailed) => throw ImageStoreFailureException(uploadFailed)
      case Left(_) => () // Let it go!
    }
  }

}
