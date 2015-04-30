package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImageSize, ImagePath, RoverImageStore }
import com.keepit.model._
import com.keepit.rover.article.{ EmbedlyArticle, Article, ArticleKind }
import com.keepit.rover.manager.{ ArticleImageProcessingTask, ArticleImageProcessingTaskQueue }
import com.keepit.rover.model._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import com.keepit.common.core._

object ArticleImageConfiguration {
  val scaleSizes = ScaledImageSize.allSizes.toSet
  val cropSizes = Set.empty[CroppedImageSize]
  val imagePathPrefix = "i"
}

case class ImageStoreFailureException(failure: ImageStoreFailure) extends Exception(failure.reason, failure.cause.orNull)

@Singleton
class ImageProcessingCommander @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    systemValueRepo: SystemValueRepo,
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    articleImageRepo: ArticleImageRepo,
    imageInfoRepo: RoverImageInfoRepo,
    fastFollowQueue: ArticleImageProcessingTaskQueue.FastFollow,
    imageStore: RoverImageStore,
    photoshop: Photoshop,
    val webService: WebService,
    private implicit val executionContext: ExecutionContext) extends ProcessedImageHelper with Logging {

  def add(tasks: Seq[ArticleImageProcessingTask], queue: ArticleImageProcessingTaskQueue): Future[Map[ArticleImageProcessingTask, Try[Unit]]] = {
    queue.add(tasks).map { maybeQueuedTasks =>
      val queuedTasks = maybeQueuedTasks.collect { case (task, Success(())) => task }.toSeq
      if (queuedTasks.nonEmpty) {
        // queues should be configured to have a very short delivery delay to make sure tasks are marked before they are consumed
        db.readWrite { implicit session =>
          articleInfoRepo.markAsImageProcessing(queuedTasks.map(_.id): _*)
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
    val result = fetchAndStoreRemoteImage(remoteImageUrl, ImageSource.RoverArticle(kind), imagePathPrefix, scaleSizes, cropSizes).map {
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

  def fetchAndStoreRemoteImage(remoteImageUrl: String, imageSource: ImageSource, imagePathPrefix: String, requiredScaleSizes: Set[ScaledImageSize] = Set.empty, requiredCropSizes: Set[CroppedImageSize] = Set.empty): Future[Either[ImageStoreFailure, ImageHash]] = {
    fetchAndHashRemoteImage(remoteImageUrl).flatMap {
      case Right(sourceImage) => {
        validateAndLoadImageFile(sourceImage.file.file) match {
          case Success(bufferedSourceImage) =>

            val sourceImageSize = ImageSize(bufferedSourceImage)
            val outFormat = inputFormatToOutputFormat(sourceImage.format)

            val existingImageInfos = db.readOnlyMaster { implicit session =>
              imageInfoRepo.getByImageHash(sourceImage.hash)
            }

            // Prepare Original Image

            val sourceImageReadyToPersistMaybe = existingImageInfos.find(_.kind == ProcessImageOperation.Original) match {
              case Some(sourceImageInfo) => Success(None)
              case None => bufferedImageToInputStream(bufferedSourceImage, sourceImage.format).map {
                case (is, bytes) =>
                  val key = ImagePath(imagePathPrefix, sourceImage.hash, sourceImageSize, ProcessImageOperation.Original, sourceImage.format)
                  Some(ImageProcessState.ReadyToPersist(key, outFormat, is, bufferedSourceImage, bytes, ProcessImageOperation.Original))
              }
            }

            sourceImageReadyToPersistMaybe match {

              case Success(sourceImageReadyToPersist) => {

                // Prepare Processed Images

                val requiredProcessRequests = {
                  val existingImageProcessRequests = existingImageInfos.collect {
                    case scaledImage if scaledImage.kind == ProcessImageOperation.Scale => ScaleImageRequest(scaledImage.imageSize)
                    case croppedImage if croppedImage.kind == ProcessImageOperation.Crop => CropImageRequest(croppedImage.imageSize)
                  }
                  val expectedProcessRequests = calcSizesForImage(sourceImageSize, requiredScaleSizes.toSeq, requiredCropSizes.toSeq)
                  filterProcessImageRequests(expectedProcessRequests, existingImageProcessRequests)
                }

                processAndPersistImages(bufferedSourceImage, imagePathPrefix, sourceImage.hash, sourceImage.format, requiredProcessRequests)(photoshop) match {

                  case Right(processedImagesReadyToPersist) => {

                    // Upload all images

                    val uploads = (processedImagesReadyToPersist ++ sourceImageReadyToPersist).map { image =>
                      imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).imap { _ =>
                        ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
                      }
                    }

                    // Update RoverImageInfo

                    Future.sequence(uploads).imap(Right(_)).recover {
                      case error: Exception => Left(ImageProcessState.CDNUploadFailed(error))
                    }.imap {
                      case Right(uploadedImages) => {
                        try {
                          db.readWrite(attempts = 3) { implicit session =>
                            uploadedImages.foreach(imageInfoRepo.intern(sourceImage.hash, imageSource, sourceImage.sourceImageUrl, _))
                          }
                          Right(sourceImage.hash)
                        } catch {
                          case imageInfoError: Exception =>
                            log.error(s"Failed to update ImageInfoRepo after fetching image from $remoteImageUrl: $imageInfoError")
                            Left(ImageProcessState.DbPersistFailed(imageInfoError))
                        }
                      }
                      case Left(uploadError) => {
                        log.error(s"Failed to upload images generated from $remoteImageUrl: $uploadError")
                        Left(uploadError)
                      }
                    }
                  }
                  case Left(processingError) => {
                    log.error(s"Failed to resize image fetched from $remoteImageUrl")
                    Future.successful(Left(processingError))
                  }
                }
              }
              case Failure(sourceImageProcessingError) => {
                log.error(s"Could not process source image fetched from $remoteImageUrl", sourceImageProcessingError)
                Future.successful(Left(ImageProcessState.InvalidImage(sourceImageProcessingError)))
              }
            }
          case Failure(validationError) => {
            log.error(s"Invalid image fetched from $remoteImageUrl", validationError)
            Future.successful(Left(ImageProcessState.InvalidImage(validationError)))
          }
        }
      }
      case Left(fetchError) => {
        log.error(s"Could not fetch remote image from $remoteImageUrl: $fetchError")
        Future.successful(Left(fetchError))
      }
    }
  }

  val imageInfoSeq = Name[SequenceNumber[ImageInfo]]("image_info_migration")
  val pathPattern = s"""i/([a-z0-9]+)_([0-9]{1,4})x([0-9]{1,4})(${ProcessImageOperation.all.map(_.fileNameSuffix).mkString("|")})[.](jpg|png)""".r
  def ingestEmbedlyImagesFromShoebox(): Future[Unit] = {
    FutureHelpers.doUntil {
      val seq = db.readOnlyMaster { implicit session =>
        systemValueRepo.getSequenceNumber(imageInfoSeq) getOrElse SequenceNumber.ZERO
      }
      shoeboxServiceClient.getImageInfosChanged(seq, 100).map {
        case Seq() => true
        case imageInfos => {
          db.readWrite { implicit session =>
            imageInfos.foreach { imageInfo =>
              if (imageInfo.state == ImageInfoStates.ACTIVE && imageInfo.provider.exists(_ == ImageProvider.EMBEDLY)) Try {

                // Parse image path
                val pathPattern(hashString, width, height, operationSuffix, formatSuffix) = imageInfo.path.path
                val hash = ImageHash(hashString)
                val imageSize = ImageSize(width.toInt, height.toInt)
                val List(processOperation) = ProcessImageOperation.all.filter(_.fileNameSuffix == operationSuffix)
                val format = formatSuffix match {
                  case "jpg" => ImageFormat.JPG
                  case "png" => ImageFormat.PNG
                }

                // If consistent image path (conservative) ingest
                if (imageInfo.getImageSize.exists(_ == imageSize) && imageInfo.format.exists(_ == format) && imageInfo.url.isDefined) {
                  imageInfoRepo.getByImage(hash, imageSize, processOperation, format) match {
                    case Some(existingInfo) => // ignore
                    case None => {
                      val newInfo = RoverImageInfo(
                        sourceImageHash = hash,
                        width = imageSize.width,
                        height = imageSize.height,
                        kind = processOperation,
                        format = format,
                        path = imageInfo.path,
                        source = ImageSource.RoverArticle(EmbedlyArticle),
                        sourceImageUrl = Some(imageInfo.url.get)
                      )
                      imageInfoRepo.save(newInfo)
                    }
                  }
                }
              }
            }
            systemValueRepo.setSequenceNumber(imageInfoSeq, imageInfos.map(_.seq).max)
          }
          false
        }
      }
    }
  }
}
