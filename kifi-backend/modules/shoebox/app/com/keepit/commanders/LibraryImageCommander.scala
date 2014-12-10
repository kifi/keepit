package com.keepit.commanders

import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImageSize, LibraryImageStore, S3ImageConfig }
import com.keepit.model._
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[LibraryImageCommanderImpl])
trait LibraryImageCommander {

  def getUrl(libraryImage: LibraryImage): String
  def getBestImageForLibrary(libraryId: Id[Library], idealSize: ImageSize): Option[LibraryImage]
  def setLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], imagePos: LibraryImagePosition, source: ImageSource, requestId: Option[Id[LibraryImageRequest]] = None): Future[ImageProcessDone]
  def positionLibraryImage(libraryId: Id[Library], imagePosition: LibraryImagePosition): Seq[LibraryImage]
  def removeImageForLibrary(libraryId: Id[Library]): Boolean // Returns true if images were removed, false otherwise
}

@Singleton
class LibraryImageCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryImageRepo: LibraryImageRepo,
    libraryImageRequestRepo: LibraryImageRequestRepo,
    libraryImageStore: LibraryImageStore,
    imageInfoRepo: ImageInfoRepo,
    s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    photoshop: Photoshop,
    val webService: WebService) extends LibraryImageCommander with ProcessedImageHelper with Logging {

  def getUrl(libraryImage: LibraryImage): String = {
    s3ImageConfig.cdnBase + "/" + libraryImage.imagePath
  }

  def getBestImageForLibrary(libraryId: Id[Library], idealSize: ImageSize): Option[LibraryImage] = {
    val validLibraryImages = db.readOnlyMaster { implicit s =>
      libraryImageRepo.getForLibraryId(libraryId)
    }
    ProcessedImageSize.pickBestImage(idealSize, validLibraryImages)
  }

  def setLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], position: LibraryImagePosition, source: ImageSource, requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(image, libraryId, position, source)(requestId).map { done =>
      finalizeImageRequestState(libraryId, requestId, done)
      done
    }
  }

  def positionLibraryImage(libraryId: Id[Library], position: LibraryImagePosition): Seq[LibraryImage] = {
    db.readWrite { implicit s =>
      libraryImageRepo.getForLibraryId(libraryId).map { libImage =>
        libraryImageRepo.save(libImage.copy(
          cropCenterX = position.centerX,
          cropCenterY = position.centerY,
          cropWidth = position.width,
          cropHeight = position.height))
      }
    }
  }

  def removeImageForLibrary(libraryId: Id[Library]): Boolean = {
    val images = db.readWrite { implicit session =>
      libraryImageRepo.getForLibraryId(libraryId).map { libImage =>
        libraryImageRepo.save(libImage.copy(state = LibraryImageStates.INACTIVE))
      }
    }
    images.nonEmpty
  }

  //
  // Internal helper methods!
  //

  private def fetchAndSet(imageFile: TemporaryFile, libraryId: Id[Library], imagePos: LibraryImagePosition, source: ImageSource)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(libraryId, imagePos, source)(fetchAndHashLocalImage(imageFile))
  }

  private def runFetcherAndPersist(libraryId: Id[Library], imagePos: LibraryImagePosition, source: ImageSource)(fetcher: => Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    updateRequestState(LibraryImageRequestStates.FETCHING)

    fetcher.flatMap {
      case Right(loadedImage) =>
        updateRequestState(LibraryImageRequestStates.PROCESSING)
        // never seen this image, or we're reprocessing an image
        buildPersistSet(loadedImage, "library")(photoshop) match {
          case Right(toPersist) =>
            updateRequestState(LibraryImageRequestStates.PERSISTING)
            uploadAndPersistImages(loadedImage, toPersist, libraryId, imagePos, source)
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], libraryId: Id[Library], selection: LibraryImagePosition, source: ImageSource): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[lic] Persisting ${image.key} (${image.bytes} B)")
      libraryImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image)
      }
    }

    Future.sequence(uploads).map { results =>
      val libraryImages = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.key.takeRight(7).indexOf(originalLabel) != -1
          val libImg = LibraryImage(libraryId = libraryId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight,
            cropCenterX = selection.centerX, cropCenterY = selection.centerY,
            cropWidth = selection.width, cropHeight = selection.height,
            source = source, sourceFileHash = originalImage.hash, isOriginal = isOriginal)
          uploadedImage.image.flush()
          libImg
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImagesForLibrary = libraryImageRepo.getForLibraryId(libraryId).toSet
        replaceOldLibraryImagesWithNew(existingImagesForLibrary, libraryImages)
      }
      ImageProcessState.StoreSuccess
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist library image", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def replaceOldLibraryImagesWithNew(oldLibraryImages: Set[LibraryImage], newLibraryImages: Set[LibraryImage])(implicit session: RWSession) = {
    if (oldLibraryImages.isEmpty) {
      newLibraryImages.map(libraryImageRepo.save)
    } else {
      val (shouldBeActive, shouldBeInactive) = oldLibraryImages.partition { existingImg =>
        newLibraryImages.find { newImg =>
          existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
        }.nonEmpty
      }
      val toActivate = shouldBeActive.filter(_.state != LibraryImageStates.ACTIVE).map(_.copy(state = LibraryImageStates.ACTIVE))
      val toDeactivate = shouldBeInactive.filter(_.state != LibraryImageStates.INACTIVE).map(_.copy(state = LibraryImageStates.INACTIVE))
      val toCreate = newLibraryImages.filter { newImg =>
        oldLibraryImages.find { existingImg =>
          existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
        }.isEmpty
      }

      log.info("[lic] Activating:" + toActivate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
      toDeactivate.foreach(libraryImageRepo.save)
      (toActivate ++ toCreate).map(libraryImageRepo.save)
    }
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
            val LibraryImageOpt = libraryImageRepo.getForLibraryId(libraryId).headOption
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
