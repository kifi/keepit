package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.OrganizationAvatarConfiguration._
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImagePath, ImageSize, OrganizationAvatarStore }
import com.keepit.model.ImageSource.UserUpload
import com.keepit.model._
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[OrganizationAvatarCommanderImpl])
trait OrganizationAvatarCommander {
  def getBestImage(orgId: Id[Organization], imageSize: ImageSize): Option[OrganizationAvatar]
  def uploadOrganizationAvatarFromFile(image: TemporaryFile, orgId: Id[Organization]): Future[ImageProcessDone]
}

@Singleton
class OrganizationAvatarCommanderImpl @Inject() (
    db: Database,
    orgAvatarRepo: OrganizationAvatarRepo,
    imageStore: OrganizationAvatarStore,
    photoshop: Photoshop,
    val webService: WebService,
    private implicit val executionContext: ExecutionContext) extends OrganizationAvatarCommander with ProcessedImageHelper with Logging {

  def getBestImage(orgId: Id[Organization], imageSize: ImageSize): Option[OrganizationAvatar] = {
    // Get the image that is the closest to the desired ImageSize, by a sum-of-squares metric
    def sq(x: Int) = x * x
    def metric(a: OrganizationAvatar) = sq(a.height - imageSize.height) + sq(a.width - imageSize.width)

    db.readOnlyReplica { implicit session =>
      val options = orgAvatarRepo.getByOrganization(orgId)
      options.sortBy(metric).headOption
    }
  }

  def uploadOrganizationAvatarFromFile(imageFile: TemporaryFile, orgId: Id[Organization]): Future[ImageProcessDone] = {
    val fetcher = fetchAndHashLocalImage(imageFile)
    fetcher.flatMap {
      case Right(loadedImage) =>
        buildPersistSet(loadedImage, imagePathPrefix, scaleSizes, cropSizes)(photoshop) match {
          case Right(toPersist) =>
            uploadAndPersistImages(loadedImage, toPersist, orgId)
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], orgId: Id[Organization]): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[oac] Persisting ${image.key} (${image.bytes} B)")
      imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
      }
    }

    Future.sequence(uploads).map { results =>
      val orgAvatars = results.map {
        case uploadedImage =>
          val orgAvatar = OrganizationAvatar(organizationId = orgId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, sourceFileHash = originalImage.hash,
            state = OrganizationAvatarStates.ACTIVE, sourceImageURL = None, kind = ProcessImageOperation.Original, source = ImageSource.UserUpload)
          uploadedImage.image.flush() // TODO: what is this for?
          orgAvatar
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImages = orgAvatarRepo.getByOrganization(orgId).toSet
        existingImages.foreach(orgAvatarRepo.deactivate)
        orgAvatars.foreach(orgAvatarRepo.save)
      }
      ImageProcessState.StoreSuccess(originalImage.format, orgAvatars.filter(_.isOriginal).head.dimensions, originalImage.file.file.length.toInt)
    }
  }

  def fetchAndStoreUploadedImage(imageFile: TemporaryFile, orgId: Id[Organization]): Future[Either[ImageStoreFailure, ImageHash]] = {
    fetchAndHashLocalImage(imageFile).flatMap {
      case Right(sourceImage) => {
        validateAndLoadImageFile(sourceImage.file.file) match {
          case Success(bufferedSourceImage) =>

            val sourceImageSize = ImageSize(bufferedSourceImage)
            val outFormat = inputFormatToOutputFormat(sourceImage.format)

            val existingImageInfos = db.readOnlyMaster { implicit session =>
              orgAvatarRepo.getByImageHash(sourceImage.hash)
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
                  val expectedProcessRequests = calcSizesForImage(sourceImageSize, scaleSizes, cropSizes)
                  filterProcessImageRequests(expectedProcessRequests, existingImageProcessRequests.toSet)
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
                            uploadedImages.foreach { img =>
                              val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.image.getWidth, height = img.image.getHeight, format = img.format, kind = ProcessImageOperation.Scale, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
                              orgAvatarRepo.save(orgAvatar)
                            }
                          }
                          Right(sourceImage.hash)
                        } catch {
                          case imageInfoError: Exception =>
                            log.error(s"Failed to update ImageInfoRepo after fetching image from user uploaded file: $imageInfoError")
                            Left(ImageProcessState.DbPersistFailed(imageInfoError))
                        }
                      }
                      case Left(uploadError) => {
                        log.error(s"Failed to upload images generated from user uploaded file: $uploadError")
                        Left(uploadError)
                      }
                    }
                  }
                  case Left(processingError) => {
                    log.error(s"Failed to resize image fetched from user uploaded file")
                    Future.successful(Left(processingError))
                  }
                }
              }
              case Failure(sourceImageProcessingError) => {
                log.error(s"Could not process source image fetched from user uploaded file", sourceImageProcessingError)
                Future.successful(Left(ImageProcessState.InvalidImage(sourceImageProcessingError)))
              }
            }
          case Failure(validationError) => {
            log.error(s"Invalid image fetched from user uploaded file", validationError)
            Future.successful(Left(ImageProcessState.InvalidImage(validationError)))
          }
        }
      }
      case Left(fetchError) => {
        log.error(s"Could not fetch remote image from user uploaded file: $fetchError")
        Future.successful(Left(fetchError))
      }
    }
  }
}

object OrganizationAvatarConfiguration {
  val scaleSizes = Seq(ScaledImageSize.Small, ScaledImageSize.Medium)
  val cropSizes = Seq.empty[CroppedImageSize]
  val imagePathPrefix = "organization"
}
