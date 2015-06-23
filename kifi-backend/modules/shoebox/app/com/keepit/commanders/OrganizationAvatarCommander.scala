package com.keepit.commanders

import java.awt.image.BufferedImage

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
      case Left(storeError) => Future.successful(Left(storeError))
      case Right(sourceImage) =>
        validateAndLoadImageFile(sourceImage.file.file) match {
          case Failure(validationError) => Future.successful(Left(ImageProcessState.InvalidImage(validationError)))
          case Success(bufferedSourceImage) =>
            persistOrganizationAvatarsFromBufferedSourceImage(sourceImage, bufferedSourceImage, orgId)
        }
    }
  }

  def persistOrganizationAvatarsFromBufferedSourceImage(sourceImage: ImageProcessState.ImageLoadedAndHashed, bufferedSourceImage: BufferedImage, orgId: Id[Organization]) = {
    val sourceImageSize = ImageSize(bufferedSourceImage)
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    val existingAvatars = db.readOnlyMaster { implicit session =>
      orgAvatarRepo.getByImageHash(sourceImage.hash)
    }
    println("[RPB] existingAvatars = " + existingAvatars)

    val (expected, necessary, unnecessary) = determineRequiredProcessImageRequests(sourceImageSize, existingAvatars)

    // Prepare Original Image
    val sourceImageReadyToPersistMaybe = existingAvatars.find(_.kind == ProcessImageOperation.Original) match {
      case Some(sourceImageInfo) => Success(None)
      case None => bufferedImageToInputStream(bufferedSourceImage, sourceImage.format).map {
        case (is, bytes) =>
          println("[RPB] No existing original image found, will have to persist this one")
          val key = ImagePath(imagePathPrefix, sourceImage.hash, sourceImageSize, ProcessImageOperation.Original, sourceImage.format)
          Some(ImageProcessState.ReadyToPersist(key, outFormat, is, bufferedSourceImage, bytes, ProcessImageOperation.Original))
      }
    }

    sourceImageReadyToPersistMaybe match {
      case Failure(sourceImageProcessingError) => Future.successful(Left(ImageProcessState.InvalidImage(sourceImageProcessingError)))
      case Success(sourceImageReadyToPersist) =>
        println("[RPB] We have to perform these processing requests: " + necessary)
        processAndPersistImages(bufferedSourceImage, imagePathPrefix, sourceImage.hash, sourceImage.format, necessary)(photoshop) match {
          case Left(processingError) => Future.successful(Left(processingError))
          case Right(processedImagesReadyToPersist) =>
            val newImagesToPersist = processedImagesReadyToPersist ++ sourceImageReadyToPersist
            println("[RPB] Done processing, ready to persist all these images: " + newImagesToPersist)
            val uploads = newImagesToPersist.map { image =>
              imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).imap { _ =>
                ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
              }
            }

            println("[RPB] Just put all the new images into the image store")

            // Update RoverImageInfo

            val uploadedImagesFut = Future.sequence(uploads).imap(Right(_)).recover {
              case error: Exception => Left(ImageProcessState.CDNUploadFailed(error))
            }
            uploadedImagesFut.imap {
              case Left(uploadError) => Left(uploadError)
              case Right(uploadedImages) => try {
                saveNewAvatars(orgId, sourceImage, existingAvatars, uploadedImages, unnecessary)
              } catch {
                case imageInfoError: Exception =>
                  log.error(s"Failed to update ImageInfoRepo after fetching image from user uploaded file: $imageInfoError")
                  Left(ImageProcessState.DbPersistFailed(imageInfoError))
              }
            }

        }
    }
  }

  def saveNewAvatars(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, existingAvatars: Seq[OrganizationAvatar], uploadedImages: Set[ImageProcessState.UploadedImage], unnecessary: Set[ProcessImageRequest]) = {
    db.readWrite(attempts = 3) { implicit session =>

      uploadedImages.foreach { img =>
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.image.getWidth, height = img.image.getHeight, format = sourceImage.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
        println("[RPB] Saved to repo: " + orgAvatar)
      }

      val originalImageOpt = existingAvatars.find(_.kind == ProcessImageOperation.Original)
      if (originalImageOpt.nonEmpty) {
        val alreadyExistingImageInfo = originalImageOpt.get
        println("[RPB] See? It was here: " + alreadyExistingImageInfo)
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = alreadyExistingImageInfo.width, height = alreadyExistingImageInfo.height, format = sourceImage.format, kind = alreadyExistingImageInfo.kind, imagePath = alreadyExistingImageInfo.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      unnecessary.foreach { processOperationRequest =>
        println("[RPB] This operation is unnecessary: " + processOperationRequest + " because it's already in the store")
        val alreadyExistingImageInfo = existingAvatars.find(avatar => avatar.kind == processOperationRequest.operation && avatar.imageSize == processOperationRequest.size).get
        println("[RPB] See? It was here: " + alreadyExistingImageInfo)
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = alreadyExistingImageInfo.width, height = alreadyExistingImageInfo.height, format = sourceImage.format, kind = alreadyExistingImageInfo.kind, imagePath = alreadyExistingImageInfo.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      Right(sourceImage.hash)
    }
  }

  // What ProcessImageRequests are necessary to perform on an image, given that we have some existing avatars already
  def determineRequiredProcessImageRequests(imageSize: ImageSize, existingAvatars: Seq[OrganizationAvatar]) = {
    val expected = {
      val scaleRequests = scaleSizes.map(scale => ScaleImageRequest(scale.idealSize))
      val cropRequests = cropSizes.map(crop => CropImageRequest(crop.idealSize))
      scaleRequests ++ cropRequests
    }.toSet
    val existing = existingAvatars.collect {
      case scaledImage if scaledImage.kind == ProcessImageOperation.Scale => ScaleImageRequest(scaledImage.imageSize)
      case croppedImage if croppedImage.kind == ProcessImageOperation.Crop => CropImageRequest(croppedImage.imageSize)
    }.toSet

    println("[RPB] Figuring out which processing requests we have to handle")
    println("[RPB] These ones are already in the store: " + existing)
    println("We want to hit: " + scaleSizes + " and " + cropSizes + " from an image of size " + imageSize)
    val necessary = diffProcessImageRequests(expected, existing)
    val unnecessary = intersectProcessImageRequests(existing, expected)
    println("[RPB] These are the ones we need to have: " + expected)
    println("[RPB] These are the ones we need to actually do: " + necessary)
    println("[RPB] These are the ones we do not need to do: " + unnecessary)

    (expected, necessary, unnecessary)
  }
  // All the ProcessImageRequests in A that are NOT in B
  def diffProcessImageRequests(A: Set[ProcessImageRequest], B: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
    @inline def boundingBox(imageSize: ImageSize): Int = Math.max(imageSize.width, imageSize.height)
    val Bp = B.collect { case ProcessImageRequest(ProcessImageOperation.Scale, size) => boundingBox(size) }
    A filterNot {
      case ProcessImageRequest(ProcessImageOperation.Scale, size) => Bp.contains(boundingBox(size))
      case otherRequest => B.contains(otherRequest)
    }
  }

  // All the ProcessImageRequests in A that ARE in B
  def intersectProcessImageRequests(A: Set[ProcessImageRequest], B: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
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
