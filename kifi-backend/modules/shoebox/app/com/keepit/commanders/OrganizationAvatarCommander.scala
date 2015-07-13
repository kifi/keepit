package com.keepit.commanders

import java.io.{ FileInputStream, InputStream }

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.OrganizationAvatarConfiguration._
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.images.{ RawImageInfo, Photoshop }
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
    implicit val photoshop: Photoshop,
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
        validateAndGetImageInfo(sourceImage.file.file) match {
          case Failure(validationError) => Future.successful(Left(ImageProcessState.InvalidImage(validationError)))
          case Success(imageInfo) =>
            persistOrganizationAvatarsFromSourceImage(sourceImage, imageInfo, orgId)
        }
    }
  }

  def persistOrganizationAvatarsFromSourceImage(sourceImage: ImageProcessState.ImageLoadedAndHashed, imageInfo: RawImageInfo, orgId: Id[Organization]): Future[Either[ImageStoreFailure, ImageHash]] = {
    val sourceImageSize = ImageSize(imageInfo.width, imageInfo.height)
    val existingAvatars = db.readOnlyMaster { implicit session =>
      orgAvatarRepo.getByImageHash(sourceImage.hash)
    }

    val (necessary, unnecessary) = determineRequiredProcessImageRequests(sourceImageSize, existingAvatars)

    prepareNewImagesToBePersisted(sourceImage, imageInfo, existingAvatars, necessary) match {
      case Left(processingError) => Future.successful(Left(processingError))
      case Right(processedImagesReadyToPersist) =>
        val uploads = processedImagesReadyToPersist.map { image =>
          val is: InputStream = new FileInputStream(image.image)

          val put = imageStore.put(image.key, is, image.bytes, imageFormatToMimeType(image.format)).imap { _ =>
            ImageProcessState.UploadedImage(image.key, image.format, image.image, image.imageInfo, image.processOperation)
          }
          put.onComplete { _ => is.close() }
          put
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

  def prepareNewImagesToBePersisted(sourceImage: ImageProcessState.ImageLoadedAndHashed, imageInfo: RawImageInfo, existingAvatars: Seq[OrganizationAvatar], necessary: Set[ProcessImageRequest]): Either[ImageStoreFailure, Set[ImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    val sourceImageSize = ImageSize(imageInfo.width, imageInfo.height)

    val sourceImageReadyToPersist = existingAvatars.find(_.kind == ProcessImageOperation.Original) match {
      case Some(sourceImageInfo) => None
      case None =>
        val key = ImagePath(imagePathPrefix, sourceImage.hash, sourceImageSize, ProcessImageOperation.Original, sourceImage.format)
        Some(ImageProcessState.ReadyToPersist(key, outFormat, sourceImage.file.file, imageInfo, sourceImage.file.file.length().toInt, ProcessImageOperation.Original))
    }

    processAndPersistImages(sourceImage.file.file, imagePathPrefix, sourceImage.hash, sourceImage.format, necessary)(photoshop) match {
      case Left(processingError) =>
        Left(processingError)
      case Right(modifiedImagesReadyToPersist) =>
        Right(modifiedImagesReadyToPersist ++ sourceImageReadyToPersist)
    }
  }

  def saveNewAvatars(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, existingAvatars: Seq[OrganizationAvatar], uploadedImages: Set[ImageProcessState.UploadedImage], unnecessary: Set[ProcessImageRequest]): Unit = {
    db.readWrite(attempts = 3) { implicit session =>
      for (oldAvatar <- orgAvatarRepo.getByOrganization(orgId)) {
        orgAvatarRepo.deactivate(oldAvatar)
      }

      for (img <- uploadedImages) {
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.imageInfo.width, height = img.imageInfo.height, format = sourceImage.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      for (originalImage <- existingAvatars.find(_.kind == ProcessImageOperation.Original)) {
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = originalImage.width, height = originalImage.height, format = originalImage.format, kind = originalImage.kind, imagePath = originalImage.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }

      for (processOperationRequest <- unnecessary) {
        val existingImage = existingAvatars.find(avatar => avatar.kind == processOperationRequest.operation && avatar.imageSize == processOperationRequest.size).get
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = existingImage.width, height = existingImage.height, format = sourceImage.format, kind = existingImage.kind, imagePath = existingImage.imagePath, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }
    }
  }

  // What ProcessImageRequests are necessary to perform on an image, given that we have some existing avatars already
  def determineRequiredProcessImageRequests(imageSize: ImageSize, existingAvatars: Seq[OrganizationAvatar]) = {
    val required = {
      val scaleRequests = scaleSizes.map(scale => ScaleImageRequest(scale.idealSize))
      val cropRequests = cropSizes.map(crop => CropImageRequest(crop.idealSize))
      scaleRequests ++ cropRequests
    }.toSet
    val existing = existingAvatars.collect {
      case scaledImage if scaledImage.kind == ProcessImageOperation.Scale => ScaleImageRequest(scaledImage.imageSize)
      case croppedImage if croppedImage.kind == ProcessImageOperation.Crop => CropImageRequest(croppedImage.imageSize)
    }.toSet

    val unnecessary = intersectProcessImageRequests(existing, required)
    val necessary = diffProcessImageRequests(required, unnecessary)

    (necessary, unnecessary)
  }
}

object OrganizationAvatarConfiguration {
  val scaleSizes = Seq(ScaledImageSize.Small, ScaledImageSize.Medium)
  val cropSizes = Seq(CroppedImageSize.Small)
  val numSizes = scaleSizes.length + cropSizes.length
  val imagePathPrefix = "oa"
}
