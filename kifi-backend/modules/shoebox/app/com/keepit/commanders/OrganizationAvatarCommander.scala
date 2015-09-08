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
import com.keepit.common.store._
import com.keepit.model.ImageSource.UserUpload
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[OrganizationAvatarCommanderImpl])
trait OrganizationAvatarCommander {
  def getBestImageByOrgId(orgId: Id[Organization], imageSize: ImageSize): OrganizationAvatar
  def getBestImagesByOrgIds(orgIds: Set[Id[Organization]], imageSize: ImageSize): Map[Id[Organization], OrganizationAvatar]
  def persistOrganizationAvatarsFromUserUpload(orgId: Id[Organization], imageFile: File, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, ImageHash]]
}

@Singleton
class OrganizationAvatarCommanderImpl @Inject() (
    db: Database,
    orgAvatarRepo: OrganizationAvatarRepo,
    imageStore: RoverImageStore,
    implicit val photoshop: Photoshop,
    val webService: WebService,
    private implicit val executionContext: ExecutionContext) extends OrganizationAvatarCommander with ProcessedImageHelper with Logging {

  def getBestImageByOrgId(orgId: Id[Organization], imageSize: ImageSize): OrganizationAvatar = {
    getBestImagesByOrgIds(Set(orgId), imageSize).head._2
  }

  def getBestImagesByOrgIds(orgIds: Set[Id[Organization]], imageSize: ImageSize): Map[Id[Organization], OrganizationAvatar] = {
    val candidatesById = db.readOnlyReplica { implicit session => orgAvatarRepo.getByOrgIds(orgIds) }
    orgIds.map { orgId =>
      val avatarOpt = ProcessedImageSize.pickByIdealImageSize(imageSize, candidatesById.get(orgId).getOrElse(defaultOrgImages(orgId)), strictAspectRatio = false)(_.imageSize)
      val avatar = avatarOpt.getOrElse(throw new Exception(s"no avatar for org $orgId with image size $imageSize"))
      orgId -> avatar
    }.toMap
  }

  private def defaultOrgImages(orgId: Id[Organization]): Seq[OrganizationAvatar] = {
    //hard coded detault image at https://djty7jcqog9qu.cloudfront.net/oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-200x200_cs.jpg
    val imageHash = ImageHash("076fccc32247ae67bb75d48879230953")
    Seq(
      OrganizationAvatar(organizationId = orgId,
        width = 100,
        height = 100,
        format = ImageFormat.JPG,
        kind = ProcessImageOperation.CropScale,
        imagePath = ImagePath("oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-100x100_cs.jpg"),
        source = ImageSource.Unknown,
        sourceFileHash = imageHash, sourceImageURL = None),
      OrganizationAvatar(organizationId = orgId,
        width = 200,
        height = 200,
        format = ImageFormat.JPG,
        kind = ProcessImageOperation.CropScale,
        imagePath = ImagePath("oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-200x200_cs.jpg"),
        source = ImageSource.Unknown,
        sourceFileHash = imageHash, sourceImageURL = None))
  }

  def persistOrganizationAvatarsFromUserUpload(orgId: Id[Organization], imageFile: File, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, ImageHash]] = {
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
                  saveNewAvatars(orgId, sourceImage.hash, uploadedImages)
                  Right(sourceImage.hash)
                } catch {
                  case repoError: Exception =>
                    log.error(s"Failed to update OrganizationAvatarRepo after processing image from user uploaded file: $repoError")
                    Left(ImageProcessState.DbPersistFailed(repoError))
                }
            }
        }
    }
  }

  private def persistOrganizationAvatarsFromSourceImage(orgId: Id[Organization], sourceImage: ImageProcessState.ImageLoadedAndHashed, imageInfo: RawImageInfo, cropRegion: SquareImageCropRegion): Future[Either[ImageStoreFailure, Set[ImageProcessState.UploadedImage]]] = {
    val necessary: Set[ProcessImageRequest] = OrganizationAvatarConfiguration.sizes.map { finalSize => CropScaleImageRequest(offset = cropRegion.offset, cropSize = cropRegion.size, finalSize = finalSize.idealSize) }
    val processedImages = processAndPersistImages(sourceImage.file, imagePathPrefix, sourceImage.hash, sourceImage.format, necessary)(photoshop)
    processedImages match {
      case Left(processingError) =>
        Future.successful(Left(processingError))
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

  private def saveNewAvatars(orgId: Id[Organization], imageHash: ImageHash, uploadedImages: Set[ImageProcessState.UploadedImage]): Unit = {
    db.readWrite(attempts = 3) { implicit session =>
      orgAvatarRepo.getByOrgId(orgId).foreach(orgAvatarRepo.deactivate)
      uploadedImages.foreach { img =>
        val orgAvatar = OrganizationAvatar(organizationId = orgId, width = img.imageInfo.width, height = img.imageInfo.height, format = img.format, kind = img.processOperation, imagePath = img.key, source = UserUpload, sourceFileHash = imageHash, sourceImageURL = None)
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
