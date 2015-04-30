package com.keepit.commanders

import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContext
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ RoverImageStore, ImageSize, S3ImageConfig }
import com.keepit.model.ProcessImageOperation.Original
import com.keepit.model._
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ ExecutionContext, Future }

object LibraryImageSizes {
  val scaleSizes = ScaledImageSize.allSizes
  val cropSizes = Seq.empty
}

@ImplementedBy(classOf[LibraryImageCommanderImpl])
trait LibraryImageCommander {

  def getUrl(libraryImage: LibraryImage): String
  def getBestImageForLibrary(libraryId: Id[Library], idealSize: ImageSize): Option[LibraryImage]
  def getBestImageForLibraries(libraryIds: Set[Id[Library]], idealSize: ImageSize): Map[Id[Library], LibraryImage]
  def uploadLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource, userId: Id[User], requestId: Option[Id[LibraryImageRequest]] = None)(implicit content: HeimdalContext): Future[ImageProcessDone]
  def positionLibraryImage(libraryId: Id[Library], position: LibraryImagePosition): Seq[LibraryImage]
  def removeImageForLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Boolean // Returns true if images were removed, false otherwise
}

@Singleton
class LibraryImageCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryImageRepo: LibraryImageRepo,
    libraryImageRequestRepo: LibraryImageRequestRepo,
    imageStore: RoverImageStore,
    libraryAnalytics: LibraryAnalytics,
    s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    photoshop: Photoshop,
    implicit val executionContext: ExecutionContext,
    val webService: WebService) extends LibraryImageCommander with ProcessedImageHelper with Logging {

  def getUrl(libraryImage: LibraryImage): String = {
    s3ImageConfig.cdnBase + "/" + libraryImage.imagePath.path
  }

  def getBestImageForLibrary(libraryId: Id[Library], idealSize: ImageSize): Option[LibraryImage] = {
    val targetLibraryImages = db.readOnlyMaster { implicit s =>
      libraryImageRepo.getActiveForLibraryId(libraryId)
    }
    ProcessedImageSize.pickBestImage(idealSize, targetLibraryImages, strictAspectRatio = false)
  }

  def getBestImageForLibraries(libraryIds: Set[Id[Library]], idealSize: ImageSize): Map[Id[Library], LibraryImage] = {
    val availableLibraryImages = db.readOnlyMaster { implicit s =>
      libraryImageRepo.getActiveForLibraryIds(libraryIds)
    }
    availableLibraryImages.mapValues(ProcessedImageSize.pickBestImage(idealSize, _, strictAspectRatio = false)).collect {
      case (libraryId, Some(image)) => libraryId -> image
    }
  }

  def uploadLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource, userId: Id[User], requestId: Option[Id[LibraryImageRequest]])(implicit context: HeimdalContext): Future[ImageProcessDone] = {
    fetchAndSet(image, libraryId, position, source)(requestId).map { done =>
      finalizeImageRequestState(libraryId, requestId, done)
      done match {
        case ImageProcessState.StoreSuccess(format, size, bytes) =>
          val targetLibrary = db.readOnlyMaster { implicit s =>
            libraryRepo.get(libraryId)
          }
          libraryAnalytics.updatedCoverImage(userId, targetLibrary, context, format, size, bytes)
        case _ =>
      }
      done
    }
  }

  def positionLibraryImage(libraryId: Id[Library], position: LibraryImagePosition): Seq[LibraryImage] = {
    db.readWrite { implicit s =>
      val toPosition = libraryImageRepo.getActiveForLibraryId(libraryId)
      val positionedImages = toPosition.map { libImage =>
        libraryImageRepo.save(libImage.copy(
          positionX = position.x,
          positionY = position.y,
          state = LibraryImageStates.ACTIVE))
      }
      log.info("[lic] Positioning: " + toPosition.map(_.imagePath))
      positionedImages
    }
  }

  def removeImageForLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Boolean = {
    val (library, images) = db.readWrite { implicit session =>
      val library = libraryRepo.get(libraryId)
      val images = libraryImageRepo.getActiveForLibraryId(libraryId).map { libImage =>
        libraryImageRepo.save(libImage.copy(state = LibraryImageStates.INACTIVE))
      }
      (library, images)
    }
    log.info("[lic] Removing: " + images.map(_.imagePath))
    if (images.nonEmpty) {
      images.filter(_.isOriginal) foreach { orig =>
        libraryAnalytics.removedCoverImage(userId, library, context, orig.format, orig.dimensions)
      }
      true
    } else {
      false
    }
  }

  //
  // Internal helper methods!
  //

  private def fetchAndSet(imageFile: TemporaryFile, libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(libraryId, position, source)(fetchAndHashLocalImage(imageFile))
  }

  private def runFetcherAndPersist(libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource)(fetcher: => Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    updateRequestState(LibraryImageRequestStates.FETCHING)

    fetcher.flatMap {
      case Right(loadedImage) =>
        updateRequestState(LibraryImageRequestStates.PROCESSING)
        buildPersistSet(loadedImage, "library", LibraryImageSizes.scaleSizes, LibraryImageSizes.cropSizes)(photoshop) match {
          case Right(toPersist) =>
            updateRequestState(LibraryImageRequestStates.PERSISTING)
            uploadAndPersistImages(loadedImage, toPersist, libraryId, position, source)
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[lic] Persisting ${image.key} (${image.bytes} B)")
      imageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
      }
    }

    Future.sequence(uploads).map { results =>
      val libraryImages = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.processOperation match {
            case Original => true
            case _ => false
          }

          val libImg = LibraryImage(libraryId = libraryId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, positionX = position.x, positionY = position.y,
            source = source, sourceFileHash = originalImage.hash, isOriginal = isOriginal, state = LibraryImageStates.ACTIVE)
          uploadedImage.image.flush()
          libImg
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImages = libraryImageRepo.getAllForLibraryId(libraryId).toSet
        replaceOldLibraryImagesWithNew(existingImages, libraryImages, position)
      }
      ImageProcessState.StoreSuccess(originalImage.format, libraryImages.filter(_.isOriginal).head.dimensions, originalImage.file.file.length.toInt)
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist library image", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def replaceOldLibraryImagesWithNew(oldLibraryImages: Set[LibraryImage], newLibraryImages: Set[LibraryImage], position: LibraryImagePosition)(implicit session: RWSession) = {
    if (oldLibraryImages.isEmpty) {
      newLibraryImages.map(libraryImageRepo.save)
    } else {
      val (shouldBeActive, shouldBeInactive) = oldLibraryImages.partition(sameHashAndWidthAndHeightAsAnyOf(newLibraryImages))
      val toUpdate = shouldBeActive
        .filter(i => i.state != LibraryImageStates.ACTIVE || i.positionX != position.x || i.positionY != position.y)
        .map(_.copy(state = LibraryImageStates.ACTIVE, positionX = position.x, positionY = position.y))
      val toDeactivate = shouldBeInactive.filter(_.state != LibraryImageStates.INACTIVE).map(_.copy(state = LibraryImageStates.INACTIVE))
      val toCreate = newLibraryImages.filterNot(sameHashAndWidthAndHeightAsAnyOf(oldLibraryImages))

      log.info("[lic] Updating:" + toUpdate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
      toDeactivate.foreach(libraryImageRepo.save)
      (toUpdate ++ toCreate).map(libraryImageRepo.save)
    }
  }

  private def sameHashAndWidthAndHeightAsAnyOf(images: Set[LibraryImage])(image: LibraryImage): Boolean = {
    images.exists(i => i.sourceFileHash == image.sourceFileHash && i.width == image.width && i.height == image.height)
  }

  private def updateRequestState(state: State[LibraryImageRequest])(implicit requestIdOpt: Option[Id[LibraryImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        libraryImageRequestRepo.updateState(requestId, state)
      }
    }
  }

  private def finalizeImageRequestState(libraryId: Id[Library], requestIdOpt: Option[Id[LibraryImageRequest]], doneResult: ImageProcessDone): Unit = {
    import com.keepit.model.ImageProcessState._
    import com.keepit.model.LibraryImageRequestStates._

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
        val request = libraryImageRequestRepo.get(requestId)
        state match {
          case INACTIVE => // Success
            val LibraryImageOpt = libraryImageRepo.getActiveForLibraryId(libraryId).headOption
            libraryImageRequestRepo.save(request.copy(state = state, successHash = LibraryImageOpt.map(_.sourceFileHash)))
          case failureState =>
            libraryImageRequestRepo.save(request.copy(state = state, failureCode = failureCode, failureReason = failureReason))
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
