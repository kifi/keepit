package com.keepit.commanders

import java.sql.SQLException

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, State }
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImageSize, RoverImageStore, S3ImageConfig }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.ProcessImageOperation.Original
import com.keepit.model._
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ ExecutionContext, Future }

object OrganizationAvatarSizes {
  val scaleSizes = ScaledImageSize.allSizes
  val cropSizes = Seq.empty
}

@ImplementedBy(classOf[OrganizationAvatarCommanderImpl])
trait OrganizationAvatarCommander {
  def getUrl(orgAvatar: OrganizationAvatar): String
  def getBestImageForOrganization(orgId: Id[Organization], idealSize: ImageSize): Option[OrganizationAvatar]
  def getBestImageForOrganizations(orgIds: Set[Id[Organization]], idealSize: ImageSize): Map[Id[Organization], OrganizationAvatar]
  def uploadOrganizationAvatarFromFile(image: TemporaryFile, orgId: Id[Organization], position: ImagePosition, source: ImageSource, userId: Id[User], requestId: Option[Id[OrganizationAvatarRequest]] = None)(implicit content: HeimdalContext): Future[ImageProcessDone]
  def positionOrganizationAvatar(orgId: Id[Organization], position: ImagePosition): Seq[OrganizationAvatar]
  def removeImageForOrganization(orgId: Id[Organization], userId: Id[User])(implicit context: HeimdalContext): Boolean // Returns true if images were removed, false otherwise
}

@Singleton
class OrganizationAvatarCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgAvatarRepo: OrganizationAvatarRepo,
    orgAvatarRequestRepo: OrganizationAvatarRequestRepo,
    imageStore: RoverImageStore,
    //orgAnalytics: OrganizationAnalytics,
    s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    photoshop: Photoshop,
    implicit val executionContext: ExecutionContext,
    val webService: WebService) extends OrganizationAvatarCommander with ProcessedImageHelper with Logging {

  def getUrl(orgAvatar: OrganizationAvatar): String = orgAvatar.imagePath.getUrl(s3ImageConfig)

  def getBestImageForOrganization(orgId: Id[Organization], idealSize: ImageSize): Option[OrganizationAvatar] = {
    val targetOrganizationAvatars = db.readOnlyMaster { implicit s =>
      orgAvatarRepo.getByOrganizationId(orgId, state = OrganizationAvatarStates.ACTIVE)
    }
    ProcessedImageSize.pickBestImage(idealSize, targetOrganizationAvatars, strictAspectRatio = false)
  }

  def getBestImageForOrganizations(orgIds: Set[Id[Organization]], idealSize: ImageSize): Map[Id[Organization], OrganizationAvatar] = {
    val availableOrganizationAvatars = db.readOnlyMaster { implicit s =>
      orgAvatarRepo.getByOrganizationIds(orgIds)
    }
    availableOrganizationAvatars.mapValues(ProcessedImageSize.pickBestImage(idealSize, _, strictAspectRatio = false)).collect {
      case (orgId, Some(image)) => orgId -> image
    }
  }

  def uploadOrganizationAvatarFromFile(image: TemporaryFile, orgId: Id[Organization], position: ImagePosition, source: ImageSource, userId: Id[User], requestId: Option[Id[OrganizationAvatarRequest]])(implicit context: HeimdalContext): Future[ImageProcessDone] = {
    fetchAndSet(image, orgId, position, source)(requestId).map { done =>
      finalizeImageRequestState(orgId, requestId, done)
      done match {
        case ImageProcessState.StoreSuccess(format, size, bytes) =>
          val targetOrganization = db.readOnlyMaster { implicit s =>
            orgRepo.get(orgId)
          }
        //orgAnalytics.updatedCoverImage(userId, targetOrganization, context, format, size, bytes)
        case _ =>
      }
      done
    }
  }

  def positionOrganizationAvatar(orgId: Id[Organization], position: ImagePosition): Seq[OrganizationAvatar] = {
    db.readWrite { implicit s =>
      val toPosition = orgAvatarRepo.getByOrganizationId(orgId)
      val positionedImages = toPosition.map { orgAvatar =>
        orgAvatarRepo.save(orgAvatar.copy(
          position = Some(position),
          state = OrganizationAvatarStates.ACTIVE))
      }
      log.info("[OrgAvatar] Positioning: " + toPosition.map(_.imagePath))
      positionedImages
    }
  }

  def removeImageForOrganization(orgId: Id[Organization], userId: Id[User])(implicit context: HeimdalContext): Boolean = {
    val (org, images) = db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val images = orgAvatarRepo.getByOrganizationId(orgId).map { orgAvatar =>
        orgAvatarRepo.save(orgAvatar.copy(state = OrganizationAvatarStates.INACTIVE))
      }
      (org, images)
    }
    log.info("[OrgAvatar] Removing: " + images.map(_.imagePath))
    if (images.nonEmpty) {
      /*
      images.filter(_.isOriginal) foreach { orig =>
        orgAnalytics.removedCoverImage(userId, org, context, orig.format, orig.dimensions)
      }
      */
      true
    } else {
      false
    }
  }

  private def fetchAndSet(imageFile: TemporaryFile, orgId: Id[Organization], position: ImagePosition, source: ImageSource)(implicit requestId: Option[Id[OrganizationAvatarRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(orgId, position, source)(fetchAndHashLocalImage(imageFile))
  }

  private def runFetcherAndPersist(orgId: Id[Organization], position: ImagePosition, source: ImageSource)(fetcher: => Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[OrganizationAvatarRequest]]): Future[ImageProcessDone] = {
    updateRequestState(OrganizationAvatarRequestStates.FETCHING)

    fetcher.flatMap {
      case Right(loadedImage) =>
        updateRequestState(OrganizationAvatarRequestStates.PROCESSING)
        buildPersistSet(loadedImage, "org", OrganizationAvatarSizes.scaleSizes, OrganizationAvatarSizes.cropSizes)(photoshop) match {
          case Right(toPersist) =>
            updateRequestState(OrganizationAvatarRequestStates.PERSISTING)
            uploadAndPersistImages(loadedImage, toPersist, orgId, position, source)
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], orgId: Id[Organization], position: ImagePosition, source: ImageSource)(implicit requestId: Option[Id[OrganizationAvatarRequest]]): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[lic] Persisting ${image.key} (${image.bytes} B)")
      imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
      }
    }

    Future.sequence(uploads).map { results =>
      val orgAvatars = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.processOperation match {
            case Original => true
            case _ => false
          }

          val orgImg = OrganizationAvatar(organizationId = orgId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, position = Some(ImagePosition(position.x, position.y)),
            source = source, sourceFileHash = originalImage.hash, state = OrganizationAvatarStates.ACTIVE,
            sourceImageURL = None, kind = ProcessImageOperation.Crop) // TODO: fix these, they're just made up
          uploadedImage.image.flush()
          orgImg
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImages = orgAvatarRepo.getByOrganizationId(orgId).toSet
        replaceOldOrganizationAvatarsWithNew(existingImages, orgAvatars, position)
      }
      ImageProcessState.StoreSuccess(originalImage.format, orgAvatars.filter(_.isOriginal).head.dimensions, originalImage.file.file.length.toInt)
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist org image", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def replaceOldOrganizationAvatarsWithNew(oldOrganizationAvatars: Set[OrganizationAvatar], newOrganizationAvatars: Set[OrganizationAvatar], position: ImagePosition)(implicit session: RWSession) = {
    if (oldOrganizationAvatars.isEmpty) {
      newOrganizationAvatars.map(orgAvatarRepo.save)
    } else {
      val (shouldBeActive, shouldBeInactive) = oldOrganizationAvatars.partition(sameHashAndWidthAndHeightAsAnyOf(newOrganizationAvatars))
      val toUpdate = shouldBeActive
        .filter(i => i.state != OrganizationAvatarStates.ACTIVE || i.position.exists(p => p.x != position.x || p.y != position.y))
        .map(_.copy(state = OrganizationAvatarStates.ACTIVE, position = Some(ImagePosition(position.x, position.y))))
      val toDeactivate = shouldBeInactive.filter(_.state != OrganizationAvatarStates.INACTIVE).map(_.copy(state = OrganizationAvatarStates.INACTIVE))
      val toCreate = newOrganizationAvatars.filterNot(sameHashAndWidthAndHeightAsAnyOf(oldOrganizationAvatars))

      log.info("[lic] Updating:" + toUpdate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
      toDeactivate.foreach(orgAvatarRepo.save)
      (toUpdate ++ toCreate).map(orgAvatarRepo.save)
    }
  }

  private def sameHashAndWidthAndHeightAsAnyOf(images: Set[OrganizationAvatar])(image: OrganizationAvatar): Boolean = {
    images.exists(i => i.sourceFileHash == image.sourceFileHash && i.width == image.width && i.height == image.height)
  }

  private def updateRequestState(state: State[OrganizationAvatarRequest])(implicit requestIdOpt: Option[Id[OrganizationAvatarRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        orgAvatarRequestRepo.updateState(requestId, state)
      }
    }
  }

  private def finalizeImageRequestState(orgId: Id[Organization], requestIdOpt: Option[Id[OrganizationAvatarRequest]], doneResult: ImageProcessDone): Unit = {
    import com.keepit.model.ImageProcessState._
    import com.keepit.model.OrganizationAvatarRequestStates._

    requestIdOpt.map { requestId =>
      val (state, failureCode, failureReason) = doneResult match {
        case err: UpstreamProviderFailed =>
          (UPSTREAM_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case UpstreamProviderNoImage =>
          (UPSTREAM_FAILED, Some(UpstreamProviderNoImage.reason), None)
        case err: SourceFetchFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: HashFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: InvalidImage =>
          (PROCESSING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: DbPersistFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: CDNUploadFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case success: ImageProcessSuccess =>
          (INACTIVE, None, None)
      }

      db.readWrite { implicit session =>
        val request = orgAvatarRequestRepo.get(requestId)
        state match {
          case INACTIVE => // Success
            val OrganizationAvatarOpt = orgAvatarRepo.getByOrganizationId(orgId).headOption
            orgAvatarRequestRepo.save(request.copy(state = state, successHash = OrganizationAvatarOpt.map(_.sourceFileHash)))
          case failureState =>
            orgAvatarRequestRepo.save(request.copy(state = state, failureCode = failureCode, failureReason = failureReason))
        }
      }
    }
  }
  private def exceptionToFailureReason(ex: Throwable) = {
    ex.getMessage + "\n\n" + ex.getStackTrace.collect {
      case t if t.getClassName.startsWith("com.keepit") =>
        t.getFileName + ":" + t.getLineNumber
    }.take(5).mkString("\n")
  }
}
