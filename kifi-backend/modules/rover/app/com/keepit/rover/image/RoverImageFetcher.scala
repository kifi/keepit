package com.keepit.rover.image

import java.io.{ InputStream, FileInputStream }

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.db.slick.Database
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImagePath, ImageSize, RoverImageStore }
import com.keepit.model._
import com.keepit.rover.model.RoverImageInfoRepo

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@Singleton
class RoverImageFetcher @Inject() (
    db: Database,
    imageInfoRepo: RoverImageInfoRepo,
    imageStore: RoverImageStore,
    implicit val photoshop: Photoshop,
    val webService: WebService,
    private implicit val executionContext: ExecutionContext) extends ProcessedImageHelper with Logging {

  def fetchAndStoreRemoteImage(remoteImageUrl: String, imageSource: ImageSource, imagePathPrefix: String, requiredScaleSizes: Set[ScaledImageSize] = Set.empty, requiredCropSizes: Set[CroppedImageSize] = Set.empty): Future[Either[ImageStoreFailure, ImageHash]] = {
    fetchAndHashRemoteImage(remoteImageUrl).flatMap {
      case Right(sourceImage) => {
        validateAndGetImageInfo(sourceImage.file.file) match {
          case Success(imageInfo) =>

            val sourceImageSize = ImageSize(imageInfo.width, imageInfo.height)
            val outFormat = inputFormatToOutputFormat(sourceImage.format)

            val existingImageInfos = db.readOnlyMaster { implicit session =>
              imageInfoRepo.getByImageHash(sourceImage.hash)
            }

            // Prepare Original Image

            val sourceImageReadyToPersist = existingImageInfos.find(_.kind == ProcessImageOperation.Original) match {
              case Some(sourceImageInfo) => None
              case None =>
                val key = ImagePath(imagePathPrefix, sourceImage.hash, sourceImageSize, ProcessImageOperation.Original, sourceImage.format)
                Some(ImageProcessState.ReadyToPersist(key, outFormat, sourceImage.file.file, imageInfo, ProcessImageOperation.Original))
            }

            // Prepare Processed Images

            val requiredProcessRequests = {
              val existingImageProcessRequests = existingImageInfos.collect {
                case scaledImage if scaledImage.kind == ProcessImageOperation.Scale => ScaleImageRequest(scaledImage.imageSize)
                case croppedImage if croppedImage.kind == ProcessImageOperation.Crop => CropImageRequest(croppedImage.imageSize)
              }
              val expectedProcessRequests = calcSizesForImage(sourceImageSize, requiredScaleSizes.toSeq, requiredCropSizes.toSeq)
              diffProcessImageRequests(expectedProcessRequests, existingImageProcessRequests)
            }

            processAndPersistImages(sourceImage.file.file, imagePathPrefix, sourceImage.hash, sourceImage.format, requiredProcessRequests)(photoshop) match {

              case Right(processedImagesReadyToPersist) => {

                // Upload all images

                val uploads = (processedImagesReadyToPersist ++ sourceImageReadyToPersist).map { image =>
                  val put = imageStore.put(image.key, image.file, imageFormatToMimeType(image.format)).imap { _ =>
                    ImageProcessState.UploadedImage(image.key, image.format, image.imageInfo, image.processOperation)
                  }
                  put
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
}
