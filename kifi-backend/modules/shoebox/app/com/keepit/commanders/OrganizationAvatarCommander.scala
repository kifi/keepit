package com.keepit.commanders

import java.io.{ FileInputStream, InputStream }

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.OrganizationAvatarConfiguration._
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.images.{ RawImageInfo, Photoshop }
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store._
import com.keepit.model.ImageSource.UserUpload
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[OrganizationAvatarCommanderImpl])
trait OrganizationAvatarCommander {
  def getBestImageByOrgId(orgId: Id[Organization], imageSize: ImageSize)(implicit session: RSession): Option[OrganizationAvatar]
  def getBestImagesByOrgIds(orgIds: Set[Id[Organization]], imageSize: ImageSize)(implicit session: RSession): Map[Id[Organization], Option[OrganizationAvatar]]
  def persistOrganizationAvatarsFromUserUpload(orgId: Id[Organization], imageFile: File, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, Set[OrganizationAvatar]]]
}

@Singleton
class OrganizationAvatarCommanderImpl @Inject() (
    db: Database,
    orgAvatarRepo: OrganizationAvatarRepo,
    imageStore: RoverImageStore,
    implicit val photoshop: Photoshop,
    val webService: WebService,
    private implicit val executionContext: ExecutionContext) extends OrganizationAvatarCommander with ProcessedImageHelper with Logging {

  def getBestImageByOrgId(orgId: Id[Organization], imageSize: ImageSize)(implicit session: RSession): Option[OrganizationAvatar] = {
    getBestImagesByOrgIds(Set(orgId), imageSize).values.head
  }
  def getBestImagesByOrgIds(orgIds: Set[Id[Organization]], imageSize: ImageSize)(implicit session: RSession): Map[Id[Organization], Option[OrganizationAvatar]] = {
    val candidatesById = orgAvatarRepo.getByOrgIds(orgIds)
    orgIds.map { orgId =>
      orgId -> ProcessedImageSize.pickByIdealImageSize(imageSize, candidatesById(orgId), strictAspectRatio = false)(_.imageSize)
    }.toMap
  }

  def persistOrganizationAvatarsFromUserUpload(orgId: Id[Organization], imageFile: File, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, Set[OrganizationAvatar]]] = {
    fetchAndHashLocalImage(imageFile).flatMap {
      case Left(storeError) => Future.successful(Left(storeError))
      case Right(sourceImage) =>
        validateAndGetImageInfo(sourceImage.file) match {
          case Failure(validationError) => Future.successful(Left(ImageProcessState.InvalidImage(validationError)))
          case Success(imageInfo) =>
            val uploadedImagesFut = persistOrganizationAvatarsFromSourceImage(orgId, sourceImage, imageInfo, cropRegion)
            uploadedImagesFut.imap {
              case Left(uploadError) => Left(uploadError)
              case Right(uploadedImages) =>
                try {
                  Right(saveNewAvatars(orgId, sourceImage, uploadedImages))
                } catch {
                  case repoError: Exception =>
                    log.error(s"Failed to update OrganizationAvatarRepo after processing image from user uploaded file: $repoError")
                    Left(ImageProcessState.DbPersistFailed(repoError))
                }
            }
        }
    }
  }

  def persistOrganizationAvatarsFromSourceImage(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, imageInfo: RawImageInfo, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, Set[ImageProcessState.UploadedImage]]] = {
    val necessary: Set[ProcessImageRequest] = OrganizationAvatarConfiguration.sizes.map { finalSize => CropScaleImageRequest(offset = cropRegion.offset, cropSize = cropRegion.size, finalSize = finalSize.idealSize) }
    val processedImages = processAndPersistImages(sourceImage.file, imagePathPrefix, sourceImage.hash, sourceImage.format, necessary)(photoshop)
    processedImages match {
      case Left(processingError) => Future.successful(Left(processingError))
      case Right(processedImagesReadyToPersist) =>
        val uploads = processedImagesReadyToPersist.map { image =>
          imageStore.put(image.key, image.file, imageFormatToMimeType(image.format)).imap { _ =>
            image.file.delete()
            ImageProcessState.UploadedImage(image.key, image.format, image.imageInfo, image.processOperation)
          }
        }
        Future.sequence(uploads).imap(Right(_)).recover { case error: Exception => Left(ImageProcessState.CDNUploadFailed(error)) }
    }
  }

  def saveNewAvatars(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, uploadedImages: Set[ImageProcessState.UploadedImage]): Set[OrganizationAvatar] = {
    db.readWrite(attempts = 3) { implicit session =>
      orgAvatarRepo.getByOrgId(orgId).foreach(orgAvatarRepo.deactivate)
      uploadedImages.map { img =>
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.imageInfo.width, height = img.imageInfo.height, format = img.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = sourceImage.hash, sourceImageURL = None)
        orgAvatarRepo.save(orgAvatar)
      }
    }
  }
}

object OrganizationAvatarConfiguration {
  val sizes = Set(CropScaledImageSize.Tiny, CropScaledImageSize.Medium)
  val defaultSize = CropScaledImageSize.Medium.idealSize
  val numSizes = sizes.size
  val imagePathPrefix = "oa"
}
