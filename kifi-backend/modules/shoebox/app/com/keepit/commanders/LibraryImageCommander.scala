package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.store.{ ImageSize, LibraryImageStore, S3ImageConfig }
import com.keepit.model._
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[LibraryImageCommanderImpl])
trait LibraryImageCommander {

  def getUrl(libraryImage: LibraryImage): String
  def getBestImageForLibrary(libraryId: Id[Library], idealSize: ImageSize): Option[LibraryImage]
  def setLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], imageSizing: LibraryImageSelection, source: BaseImageSource, requestId: Option[Id[LibraryImageRequest]] = None): Future[BaseImageProcessDone]
  def setLibraryImageSizing(libraryId: Id[Library], imageSizing: LibraryImageSelection): Seq[LibraryImage]
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
    ProcessedImageSize.pickBestImage(idealSize, validLibraryImages).asInstanceOf[Option[LibraryImage]]
  }

  def setLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], selection: LibraryImageSelection, source: BaseImageSource, requestId: Option[Id[LibraryImageRequest]]): Future[BaseImageProcessDone] = {
    fetchAndSet(image, libraryId, selection, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(libraryId, requestId, done)
      done
    }
  }

  def setLibraryImageSizing(libraryId: Id[Library], selection: LibraryImageSelection): Seq[LibraryImage] = {
    db.readWrite { implicit s =>
      libraryImageRepo.getForLibraryId(libraryId).map { libImage =>
        libraryImageRepo.save(libImage.copy(
          centerX = selection.centerX,
          centerY = selection.centerY,
          selectedWidth = selection.selectedWidth,
          selectedHeight = selection.selectedHeight))
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

  private def fetchAndSet(imageFile: TemporaryFile, libraryId: Id[Library], selection: LibraryImageSelection, source: BaseImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[BaseImageProcessDone] = {
    runFetcherAndPersist(libraryId, selection, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }

  private def runFetcherAndPersist(libraryId: Id[Library], selection: LibraryImageSelection, source: BaseImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[BaseImageStoreFailure, BaseImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[LibraryImageRequest]]): Future[BaseImageProcessDone] = {
    val existingImagesForLibrary = db.readOnlyMaster { implicit session =>
      libraryImageRepo.getForLibraryId(libraryId)
    }
    if (existingImagesForLibrary.nonEmpty && !overwriteExistingImage) {
      Future.successful(BaseImageProcessState.ExistingStoredImagesFound(existingImagesForLibrary))
    } else {
      updateRequestState(LibraryImageRequestStates.FETCHING)

      fetcher.flatMap {
        case Right(loadedImage) =>
          updateRequestState(LibraryImageRequestStates.PROCESSING)
          val existingSameHash = db.readOnlyReplica { implicit session =>
            libraryImageRepo.getBySourceHash(loadedImage.hash)
          }
          if (existingSameHash.isEmpty || overwriteExistingImage) {
            // never seen this image, or we're reprocessing an image
            buildPersistSet(loadedImage, "library")(photoshop) match {
              case Right(toPersist) =>
                updateRequestState(LibraryImageRequestStates.PERSISTING)
                uploadAndPersistImages(loadedImage, toPersist, libraryId, selection, source, overwriteExistingImage)
              case Left(failure) =>
                Future.successful(failure)
            }
          } else {
            // have existing LibraryImages, use those
            log.info(s"[kic] Existing stored images found: $existingSameHash")
            Future.successful(copyExistingImagesAndReplace(libraryId, selection, source, existingSameHash))
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def uploadAndPersistImages(originalImage: BaseImageProcessState.ImageLoadedAndHashed, toPersist: Set[BaseImageProcessState.ReadyToPersist], libraryId: Id[Library], selection: LibraryImageSelection, source: BaseImageSource, overwriteExistingImage: Boolean): Future[BaseImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[kic] Persisting ${image.key} (${image.bytes} B)")
      libraryImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        BaseImageProcessState.UploadedImage(image.key, image.format, image.image)
      }
    }

    Future.sequence(uploads).map { results =>
      val libraryImages = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.key.takeRight(7).indexOf(originalLabel) != -1
          val libImg = LibraryImage(libraryId = libraryId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight,
            centerX = selection.centerX, centerY = selection.centerY,
            selectedWidth = selection.selectedWidth, selectedHeight = selection.selectedHeight,
            source = source, sourceImageUrl = originalImage.sourceImageUrl, sourceFileHash = originalImage.hash, isOriginal = isOriginal)
          uploadedImage.image.flush()
          libImg
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImagesForLibrary = libraryImageRepo.getForLibraryId(libraryId).toSet
        replaceOldLibraryImagesWithNew(existingImagesForLibrary, libraryImages)
      }
      BaseImageProcessState.StoreSuccess
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist library image", ex)
        BaseImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        BaseImageProcessState.CDNUploadFailed(ex)
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

      log.info("[kic] Activating:" + toActivate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
      toDeactivate.foreach(libraryImageRepo.save)
      (toActivate ++ toCreate).map(libraryImageRepo.save)
    }
  }

  private def copyExistingImagesAndReplace(libraryId: Id[Library], selection: LibraryImageSelection, source: BaseImageSource, existingSameHash: Seq[LibraryImage]) = {
    val allForThisLibrary = existingSameHash.filter(i => i.libraryId == libraryId)
    val activeForThisLibrary = allForThisLibrary.filter(i => i.state == LibraryImageStates.ACTIVE)
    if (activeForThisLibrary.nonEmpty) {
      BaseImageProcessState.ExistingStoredImagesFound(activeForThisLibrary)
    } else if (allForThisLibrary.nonEmpty) {
      val saved = db.readWrite { implicit session =>
        allForThisLibrary.map(_.copy(state = LibraryImageStates.ACTIVE)).map { img => libraryImageRepo.save(img) }
      }
      BaseImageProcessState.ExistingStoredImagesFound(saved)
    } else {
      val copiedImages = existingSameHash.map { prev =>
        LibraryImage(state = LibraryImageStates.ACTIVE, libraryId = libraryId, imagePath = prev.imagePath, format = prev.format,
          width = prev.width, height = prev.height,
          centerX = selection.centerX, centerY = selection.centerY, selectedWidth = selection.selectedWidth, selectedHeight = selection.selectedHeight,
          source = source, sourceFileHash = prev.sourceFileHash, sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal)
      }

      val saved = db.readWrite { implicit session =>
        val existingForKeep = libraryImageRepo.getForLibraryId(libraryId)
        existingForKeep.map { oldImg =>
          libraryImageRepo.save(oldImg.copy(state = LibraryImageStates.INACTIVE))
        }
        copiedImages.map { img =>
          libraryImageRepo.save(img)
        }
      }
      BaseImageProcessState.ExistingStoredImagesFound(saved)
    }
  }

  private def updateRequestState(state: State[LibraryImageRequest])(implicit requestIdOpt: Option[Id[LibraryImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        libraryImageRequestRepo.updateState(requestId, state)
      }
    }
  }

  private def finalizeImageRequestState(libraryId: Id[Library], requestIdOpt: Option[Id[LibraryImageRequest]], doneResult: BaseImageProcessDone): Unit = {
    import com.keepit.model.BaseImageProcessState._
    import com.keepit.model.LibraryImageRequestStates._

    requestIdOpt.map { requestId =>
      val (state, failureCode, failureReason) = doneResult match {
        case err: UpstreamProviderFailed =>
          (UPSTREAM_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case UpstreamProviderNoImage$ =>
          (UPSTREAM_FAILED, Some(UpstreamProviderNoImage$.reason), None)
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
        case success: BaseImageProcessSuccess =>
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
