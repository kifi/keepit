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

  def getBestImage(orgId: Id[Organization], idealSize: ImageSize): Option[OrganizationAvatar] = {
    val candidates = db.readOnlyReplica { implicit session => orgAvatarRepo.getByOrganization(orgId) }
    ProcessedImageSize.pickByIdealImageSize(idealSize, candidates, strictAspectRatio = false)(_.imageSize)
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

  def persistOrganizationAvatarsFromBufferedSourceImage(sourceImage: ImageProcessState.ImageLoadedAndHashed, bufferedSourceImage: BufferedImage, orgId: Id[Organization]): Future[Either[ImageStoreFailure, ImageHash]] = {
    val sourceImageSize = ImageSize(bufferedSourceImage)
    val existingAvatars = db.readOnlyMaster { implicit session =>
      orgAvatarRepo.getByImageHash(sourceImage.hash)
    }

    val (necessary, unnecessary) = determineRequiredProcessImageRequests(sourceImageSize, existingAvatars)

    prepareNewImagesToBePersisted(sourceImage, bufferedSourceImage, existingAvatars, necessary) match {
      case Left(processingError) => Future.successful(Left(processingError))
      case Right(processedImagesReadyToPersist) =>
        val uploads = processedImagesReadyToPersist.map { image =>
          imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).imap { _ =>
            ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
          }
        }

        val uploadedImagesFut = Future.sequence(uploads).imap(Right(_)).recover { case error: Exception => Left(ImageProcessState.CDNUploadFailed(error)) }
        uploadedImagesFut.imap {
          case Left(uploadError) => Left(uploadError)
          case Right(uploadedImages) =>
            try {
              saveNewAvatars(orgId, sourceImage, existingAvatars, uploadedImages, unnecessary)
              Right(sourceImage.hash)
            } catch {
              case repoError: Exception =>
                log.error(s"Failed to update OrganizationAvatarRepo after processing image from user uploaded file: $repoError")
                Left(ImageProcessState.DbPersistFailed(repoError))
            }
        }
    }
  }

  def prepareNewImagesToBePersisted(sourceImage: ImageProcessState.ImageLoadedAndHashed, bufferedSourceImage: BufferedImage, existingAvatars: Seq[OrganizationAvatar], necessary: Set[ProcessImageRequest]): Either[ImageStoreFailure, Set[ImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    val sourceImageReadyToPersistMaybe = existingAvatars.find(_.kind == ProcessImageOperation.Original) match {
      case Some(sourceImageInfo) => Success(None)
      case None => bufferedImageToInputStream(bufferedSourceImage, sourceImage.format).map {
        case (is, bytes) =>
          val key = ImagePath(imagePathPrefix, sourceImage.hash, ImageSize(bufferedSourceImage), ProcessImageOperation.Original, sourceImage.format)
          Some(ImageProcessState.ReadyToPersist(key, outFormat, is, bufferedSourceImage, bytes, ProcessImageOperation.Original))
      }
    }

    sourceImageReadyToPersistMaybe match {
      case Failure(sourceImageProcessingError) =>
        Left(ImageProcessState.InvalidImage(sourceImageProcessingError))

      case Success(sourceImageReadyToPersist) =>
        processAndPersistImages(bufferedSourceImage, imagePathPrefix, sourceImage.hash, sourceImage.format, necessary)(photoshop) match {
          case Left(processingError) =>
            Left(processingError)
          case Right(modifiedImagesReadyToPersist) =>
            Right(modifiedImagesReadyToPersist ++ sourceImageReadyToPersist)
        }
    }
  }

  def saveNewAvatars(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, existingAvatars: Seq[OrganizationAvatar], uploadedImages: Set[ImageProcessState.UploadedImage], unnecessary: Set[ProcessImageRequest]) = {
    db.readWrite(attempts = 3) { implicit session =>

      uploadedImages.foreach { img =>
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.image.getWidth, height = img.image.getHeight, format = sourceImage.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      val originalImageOpt = existingAvatars.find(_.kind == ProcessImageOperation.Original)
      if (originalImageOpt.nonEmpty) {
        val alreadyExistingImageInfo = originalImageOpt.get
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = alreadyExistingImageInfo.width, height = alreadyExistingImageInfo.height, format = sourceImage.format, kind = alreadyExistingImageInfo.kind, imagePath = alreadyExistingImageInfo.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      unnecessary.foreach { processOperationRequest =>
        val alreadyExistingImageInfo = existingAvatars.find(avatar => avatar.kind == processOperationRequest.operation && avatar.imageSize == processOperationRequest.size).get
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

    val necessary = diffProcessImageRequests(expected, existing)
    val unnecessary = intersectProcessImageRequests(existing, expected)

    (necessary, unnecessary)
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
