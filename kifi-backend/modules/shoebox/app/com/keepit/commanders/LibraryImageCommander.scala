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
import play.api.http.Status._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[LibraryImageCommanderImpl])
trait LibraryImageCommander {

  def getUrl(libraryImage: LibraryImage): String
  def getBestImageForLibraryAndHash(libraryId: Id[Library], hash: ImageHash, idealSize: ImageSize): Option[LibraryImage]
  def uploadLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], source: ImageSource, requestId: Option[Id[LibraryImageRequest]] = None): Future[ImageProcessDone]
  def positionLibraryImage(libraryId: Id[Library], imageHash: ImageHash, imagePosition: LibraryImagePosition): Either[(Int, String), Seq[LibraryImage]]
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

  def getBestImageForLibraryAndHash(libraryId: Id[Library], hash: ImageHash, idealSize: ImageSize): Option[LibraryImage] = {
    val targetLibraryImages = db.readOnlyMaster { implicit s =>
      libraryImageRepo.getByLibraryIdAndHash(libraryId, hash)
    }
    ProcessedImageSize.pickBestImage(idealSize, targetLibraryImages)
  }

  def uploadLibraryImageFromFile(image: TemporaryFile, libraryId: Id[Library], source: ImageSource, requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(image, libraryId, source)(requestId).map { done =>
      finalizeImageRequestState(libraryId, requestId, done)
      done
    }
  }

  def positionLibraryImage(libraryId: Id[Library], imageHash: ImageHash, position: LibraryImagePosition): Either[(Int, String), Seq[LibraryImage]] = {
    db.readWrite { implicit s =>
      val (toPosition, toDeactivate) = libraryImageRepo.getForLibraryIdOrWithHash(libraryId, imageHash).partition { img =>
        img.sourceFileHash == imageHash
      }

      if (toPosition.isEmpty) {
        Left((NOT_FOUND, "hash_not_found"))
      } else {
        toDeactivate.map { libImage =>
          libraryImageRepo.save(libImage.copy(state = LibraryImageStates.INACTIVE))
        }
        val positionedImages = toPosition.map { libImage =>
          val setX = position.x.getOrElse(libImage.positionX.getOrElse(50))
          val setY = position.y.getOrElse(libImage.positionY.getOrElse(50))
          libraryImageRepo.save(libImage.copy(
            positionX = Some(setX),
            positionY = Some(setY),
            state = LibraryImageStates.ACTIVE))
        }
        log.info("[lic] Deactivating: " + toDeactivate.map(_.imagePath) + "\nPositioning: " + toPosition.map(_.imagePath))
        Right(positionedImages)
      }
    }
  }

  def removeImageForLibrary(libraryId: Id[Library]): Boolean = {
    val images = db.readWrite { implicit session =>
      libraryImageRepo.getForLibraryId(libraryId).map { libImage =>
        libraryImageRepo.save(libImage.copy(state = LibraryImageStates.INACTIVE))
      }
    }
    log.info("[lic] Removing: " + images.map(_.imagePath))
    images.nonEmpty
  }

  //
  // Internal helper methods!
  //

  private def fetchAndSet(imageFile: TemporaryFile, libraryId: Id[Library], source: ImageSource)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(libraryId, source)(fetchAndHashLocalImage(imageFile))
  }

  private def runFetcherAndPersist(libraryId: Id[Library], source: ImageSource)(fetcher: => Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
    updateRequestState(LibraryImageRequestStates.FETCHING)

    fetcher.flatMap {
      case Right(loadedImage) =>
        updateRequestState(LibraryImageRequestStates.PROCESSING)
        // never seen this image, or we're reprocessing an image
        buildPersistSet(loadedImage, "library")(photoshop) match {
          case Right(toPersist) =>
            updateRequestState(LibraryImageRequestStates.PERSISTING)
            uploadAndPersistImages(loadedImage, toPersist, libraryId, source)
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], libraryId: Id[Library], source: ImageSource)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[ImageProcessDone] = {
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
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, positionX = None, positionY = None,
            source = source, sourceFileHash = originalImage.hash, isOriginal = isOriginal, state = LibraryImageStates.INACTIVE)
          uploadedImage.image.flush()
          libImg
      }
      val createdImages = db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingHashesForLibrary = libraryImageRepo.getForLibraryId(libraryId, None).map(_.sourceFileHash).toSet
        val newImages = libraryImages.filterNot { img =>
          existingHashesForLibrary.contains(img.sourceFileHash)
        }
        newImages.map { libImg =>
          libraryImageRepo.save(libImg)
        }
      }
      log.info("[lic] Creating: " + createdImages.map(_.imagePath))
      ImageProcessState.StoreSuccess(libraryImages.map(_.sourceFileHash))
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist library image", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
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
