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
  def persistOrganizationAvatarsFromUserUpload(imageFile: TemporaryFile, orgId: Id[Organization]): Future[Either[ImageStoreFailure, ImageHash]]
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

  def persistOrganizationAvatarsFromUserUpload(imageFile: TemporaryFile, orgId: Id[Organization]): Future[Either[ImageStoreFailure, ImageHash]] = {
    fetchAndHashLocalImage(imageFile).flatMap {
      case Right(sourceImage) =>
        validateAndLoadImageFile(sourceImage.file.file) match {
          case Success(bufferedSourceImage) =>

            val sourceImageSize = ImageSize(bufferedSourceImage)
            val outFormat = inputFormatToOutputFormat(sourceImage.format)

            val existingImageInfos = db.readOnlyMaster { implicit session =>
              orgAvatarRepo.getByImageHash(sourceImage.hash)
            }
            println("[RPB] existingImageInfo = " + existingImageInfos)

            val expectedProcessRequests = calcSizesForImage(sourceImageSize, scaleSizes, cropSizes)
            val existingImageProcessRequests = existingImageInfos.collect {
              case scaledImage if scaledImage.kind == ProcessImageOperation.Scale => ScaleImageRequest(scaledImage.imageSize)
              case croppedImage if croppedImage.kind == ProcessImageOperation.Crop => CropImageRequest(croppedImage.imageSize)
            }
            val (necessaryProcessRequests, unnecessaryProcessRequests) = {
              println("[RPB] Figuring out which processing requests we have to handle")
              println("[RPB] These ones are already in the store: " + existingImageProcessRequests)
              println("We want to hit: " + scaleSizes + " and " + cropSizes + " from an image of size " + sourceImageSize)
              println("[RPB] These are the ones we need to have: " + expectedProcessRequests)
              val necessaryProcessRequests = filterProcessImageRequests(expectedProcessRequests, existingImageProcessRequests.toSet)
              println("[RPB] These are the ones we need to actually do: " + necessaryProcessRequests)
              val unnecessaryProcessRequests = intersectProcessImageRequests(existingImageProcessRequests.toSet, expectedProcessRequests)
              println("[RPB] These are the ones we do not need to do: " + unnecessaryProcessRequests)
              (necessaryProcessRequests, unnecessaryProcessRequests)
            }

            // Prepare Original Image

            val sourceImageReadyToPersistMaybe = existingImageInfos.find(_.kind == ProcessImageOperation.Original) match {
              case Some(sourceImageInfo) => Success(None)
              case None => bufferedImageToInputStream(bufferedSourceImage, sourceImage.format).map {
                case (is, bytes) =>
                  println("[RPB] No existing original image found, will have to persist this one")
                  val key = ImagePath(imagePathPrefix, sourceImage.hash, sourceImageSize, ProcessImageOperation.Original, sourceImage.format)
                  Some(ImageProcessState.ReadyToPersist(key, outFormat, is, bufferedSourceImage, bytes, ProcessImageOperation.Original))
              }
            }

            sourceImageReadyToPersistMaybe match {

              case Success(sourceImageReadyToPersist) =>

                // Prepare Processed Images

                println("[RPB] We have to perform these processing requests: " + necessaryProcessRequests)
                processAndPersistImages(bufferedSourceImage, imagePathPrefix, sourceImage.hash, sourceImage.format, necessaryProcessRequests)(photoshop) match {

                  case Right(processedImagesReadyToPersist) =>

                    // Upload all images
                    val newImagesToPersist = processedImagesReadyToPersist ++ sourceImageReadyToPersist
                    println("[RPB] Done processing, ready to persist all these images: " + newImagesToPersist)
                    val uploads = newImagesToPersist.map { image =>
                      imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).imap { _ =>
                        ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
                      }
                    }

                    println("[RPB] Just put all the new images into the image store")

                    // Update RoverImageInfo

                    Future.sequence(uploads).imap(Right(_)).recover {
                      case error: Exception => Left(ImageProcessState.CDNUploadFailed(error))
                    }.imap {
                      case Right(uploadedImages) =>
                        try {
                          db.readWrite(attempts = 3) { implicit session =>
                            uploadedImages.foreach { img =>
                              val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.image.getWidth, height = img.image.getHeight, format = sourceImage.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
                              orgAvatarRepo.save(orgAvatar)
                              println("[RPB] Saved to repo: " + orgAvatar)
                            }
                            val originalImageOpt = existingImageInfos.find(_.kind == ProcessImageOperation.Original)
                            if (originalImageOpt.nonEmpty) {
                              val alreadyExistingImageInfo = originalImageOpt.get
                              println("[RPB] See? It was here: " + alreadyExistingImageInfo)
                              val orgAvatar = OrganizationAvatar(organizationId = orgId, width = alreadyExistingImageInfo.width, height = alreadyExistingImageInfo.height, format = sourceImage.format, kind = alreadyExistingImageInfo.kind, imagePath = alreadyExistingImageInfo.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
                              orgAvatarRepo.save(orgAvatar)
                            }
                            unnecessaryProcessRequests.foreach { processOperationRequest =>
                              println("[RPB] This operation is unnecessary: " + processOperationRequest + " because it's already in the store")
                              val alreadyExistingImageInfo = existingImageInfos.find(avatar => avatar.kind == processOperationRequest.operation && avatar.imageSize == processOperationRequest.size).get
                              println("[RPB] See? It was here: " + alreadyExistingImageInfo)
                              val orgAvatar = OrganizationAvatar(organizationId = orgId, width = alreadyExistingImageInfo.width, height = alreadyExistingImageInfo.height, format = sourceImage.format, kind = alreadyExistingImageInfo.kind, imagePath = alreadyExistingImageInfo.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
                              orgAvatarRepo.save(orgAvatar)
                            }
                          }
                          println("[RPB] Wrote all the persisted image info to the avatar repo")
                          Right(sourceImage.hash)
                        } catch {
                          case imageInfoError: Exception =>
                            log.error(s"Failed to update ImageInfoRepo after fetching image from user uploaded file: $imageInfoError")
                            Left(ImageProcessState.DbPersistFailed(imageInfoError))
                        }
                      case Left(uploadError) =>
                        log.error(s"Failed to upload images generated from user uploaded file: $uploadError")
                        Left(uploadError)
                    }

                  case Left(processingError) => {
                    log.error(s"Failed to resize image fetched from user uploaded file")
                    Future.successful(Left(processingError))
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
      case Left(storeError) => {
        log.error(s"Could not store image from user uploaded file: $storeError")
        Future.successful(Left(storeError))
      }
    }
  }
  override def calcSizesForImage(imageSize: ImageSize, scaleCandidates: Seq[ScaledImageSize], cropCandidates: Seq[CroppedImageSize]): Set[ProcessImageRequest] = {
    val scaleSizes = scaleCandidates.flatMap { size =>
      calcResizeBoundingBox(imageSize, size.idealSize)
    }
    println("[RPB''] In calcSizes we have scaleSizes = " + scaleSizes)

    val scaleImageRequests = {
      var t = 0
      scaleSizes.sorted.flatMap { x =>
        if (x - t > 100) {
          t = x
          Some(x)
        } else None
      }.filterNot { i => i == Math.max(imageSize.width, imageSize.height) }.
        map { x => ScaleImageRequest(x) }
    }

    val imgHeight = imageSize.height
    val imgWidth = imageSize.width

    log.info(s"[csfi] imageSize=${imageSize.width}x${imageSize.height} cropCandidates=$cropCandidates")
    val cropImageRequests = cropCandidates.filterNot { cropSize =>
      val size = cropSize.idealSize
      def isAlmostSameAspectRatio = Math.abs(imgWidth.toFloat / imgHeight - cropSize.aspectRatio) < 0.01

      // 1) if either the width or height of the actual image is smaller than our crop, abort
      // 2) or, if the aspect ratio of the image and crop size are the same and there exists
      //    a scale bounding box close to enough to our crop candidate, we can skip the crop
      imgWidth < size.width || imgHeight < size.height ||
        (isAlmostSameAspectRatio && scaleImageRequests.exists { scaleSize =>
          val candidateCropWidth = cropSize.idealSize.width
          val candidateCropHeight = cropSize.idealSize.height
          val scaleWidth = scaleSize.size.width
          val scaleHeight = scaleSize.size.height

          scaleWidth >= candidateCropWidth && scaleHeight >= candidateCropHeight &&
            scaleWidth - candidateCropWidth < 100 && scaleHeight - candidateCropHeight < 100
        })
    }.map { c => CropImageRequest(c.idealSize) }

    log.info(s"[csfi] imageSize=${imageSize.width}x${imageSize.height} cropRequests=$cropImageRequests")
    (scaleImageRequests ++ cropImageRequests).toSet
  }
  override def calcResizeBoundingBox(imageSize: ImageSize, size: ImageSize): Option[Int] = {
    println("[RPB'] Using overriden method")
    val imgHeight = imageSize.height
    val imgWidth = imageSize.width

    val fudgeFactor = 0.00

    if (size.height * fudgeFactor > imgHeight && size.width * fudgeFactor > imgWidth) {
      // The size we want is just too far from the original.
      None
    } else {
      val h2 = Math.min(size.height, imgHeight)
      val w2 = Math.min(size.width, imgWidth)
      Some(Math.max(h2, w2))
    }
  }

  def intersectProcessImageRequests(A: Set[ProcessImageRequest], B: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
    // hack to eliminate expecting scale versions that have the same scaled bounding box
    @inline def boundingBox(imageSize: ImageSize): Int = Math.max(imageSize.width, imageSize.height)
    val Bp = B.collect { case ProcessImageRequest(ProcessImageOperation.Scale, size) => boundingBox(size) }
    A filter {
      case ProcessImageRequest(ProcessImageOperation.Scale, size) => Bp.contains(boundingBox(size))
      case otherRequest => B.contains(otherRequest)
    }
  }
}

object OrganizationAvatarConfiguration {
  val scaleSizes = Seq(ScaledImageSize.Small, ScaledImageSize.Medium)
  val cropSizes = Seq(CroppedImageSize.Small)
  val numSizes = scaleSizes.length + cropSizes.length
  val imagePathPrefix = "organization"
}
