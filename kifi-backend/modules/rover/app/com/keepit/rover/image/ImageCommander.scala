package com.keepit.rover.image

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ ImagePath, ImageSize, S3ImageConfig }
import com.keepit.model._
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.manager.{ ArticleImageProcessingTask, ArticleImageProcessingTaskQueue }
import com.keepit.rover.model._

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

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
    imageInfoRepo: RoverImageInfoRepo,
    fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow,
    imageFetcher: RoverImageFetcher,
    private implicit val executionContext: ExecutionContext) extends Logging {

  def getImageInfosByUrisAndArticleKind[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Map[Id[NormalizedURI], Set[RoverImageInfo]] = {
    db.readOnlyMaster { implicit session =>
      val imageHashesByUriId = articleImageRepo.getByUris(uriIds).mapValues { articleImages =>
        articleImages.collect { case articleImage if articleImage.articleKind == kind => articleImage.imageHash }
      }
      val imagesByHash = imageInfoRepo.getByImageHashes(imageHashesByUriId.values.flatten.toSet)
      imageHashesByUriId.mapValues(_.flatMap(imagesByHash.getOrElse(_, Set.empty)))
    }
  }

  // Image Fetching Functions

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

  def getArticleInfosForImageProcessing(limit: Int, fetchedForMoreThan: Duration, imageProcessingForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForImageProcessing(limit, fetchedForMoreThan, imageProcessingForMoreThan)
    }
  }

  def processArticleImagesAsap(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
    val tasks = ids.map(ArticleImageProcessingTask(_)).toSeq
    add(tasks, fastFollowQueue).imap { _.map { case (task, result) => (task.id -> result) } }
  }

  def processRemoteArticleImage(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], version: ArticleVersion, remoteImageUrl: String): Future[Unit] = {
    import com.keepit.rover.image.ArticleImageConfiguration._
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
