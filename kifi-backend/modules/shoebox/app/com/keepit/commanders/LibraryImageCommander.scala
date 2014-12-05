package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.sql.SQLException

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ LibraryImageStore, S3ImageConfig, S3URIImageStore }
import com.keepit.model._
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[LibraryImageCommanderImpl])
trait LibraryImageCommander {

  def getUrl(libraryImage: LibraryImage): String
  //def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[Option[LibraryImage]]
  //def getBestImagesForKeeps(keepIds: Set[Id[Keep]], idealSize: ImageSize): Map[Id[Keep], Option[LibraryImage]]
  //def getExistingImageUrlForLibrary(libraryId: Id[Library])(implicit session: RSession): Option[String]

  //def autoSetLibraryImage(libraryId: Id[Library], localOnly: Boolean = true, overwriteExistingChoice: Boolean = false): Future[ImageProcessDone]
  //def setLibraryImageFromUrl(imageUrl: String, keepId: Id[Keep], source: LibraryImageSource, requestId: Option[Id[LibraryImageRequest]] = None): Future[ImageProcessDone]
  def setImageFromFile(image: TemporaryFile, libraryId: Id[Library], source: LibraryImageSource, requestId: Option[Id[LibraryImageRequest]] = None): Future[LibraryImageProcessDone]

  // Returns true if images were removed, false otherwise
  def removeImageForLibrary(libraryId: Id[Library]): Boolean

}

@Singleton
class LibraryImageCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryImageRepo: LibraryImageRepo,
    libraryImageRequestRepo: LibraryImageRequestRepo,
    libraryImageStore: LibraryImageStore,
    imageInfoRepo: ImageInfoRepo,
    s3UriImageStore: S3URIImageStore,
    s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    airbrake: AirbrakeNotifier,
    photoshop: Photoshop,
    val webService: WebService) extends LibraryImageCommander with ProcessedImageHelper with Logging {

  def getUrl(libraryImage: LibraryImage): String = {
    s3ImageConfig.cdnBase + "/" + libraryImage.imagePath
  }

  def setImageFromFile(image: TemporaryFile, libraryId: Id[Library], source: LibraryImageSource, requestId: Option[Id[LibraryImageRequest]]): Future[LibraryImageProcessDone] = {
    fetchAndSet(image, libraryId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(libraryId, requestId, done)
      done
    }
  }

  def removeImageForLibrary(libraryId: Id[Library]): Boolean = {
    val images = db.readWrite { implicit session =>
      libraryImageRepo.getForLibraryId(libraryId).map { libraryImage =>
        libraryImageRepo.save(libraryImage.copy(state = LibraryImageStates.INACTIVE))
      }
    }
    images.nonEmpty
  }

  val originalLabel: String = "_o"

  private def fetchAndSet(imageFile: TemporaryFile, libraryId: Id[Library], source: LibraryImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[LibraryImageRequest]]): Future[LibraryImageProcessDone] = {
    runFetcherAndPersist(libraryId, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }
  private def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[LibraryImageStoreFailure, LibraryImageProcessState.LibraryImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching ${file.file.getAbsolutePath}")

    val formatOpt = detectImageType(file)

    formatOpt match {
      case Some(format) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Future.successful(Right(LibraryImageProcessState.LibraryImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(LibraryImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(LibraryImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }
  private def runFetcherAndPersist(libraryId: Id[Library], source: LibraryImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[LibraryImageStoreFailure, LibraryImageProcessState.LibraryImageLoadedAndHashed]])(implicit requestId: Option[Id[LibraryImageRequest]]): Future[LibraryImageProcessDone] = {
    val existingImagesForLibrary = db.readOnlyMaster { implicit session =>
      libraryImageRepo.getForLibraryId(libraryId)
    }
    if (existingImagesForLibrary.nonEmpty && !overwriteExistingImage) {
      Future.successful(LibraryImageProcessState.ExistingStoredImagesFound(existingImagesForLibrary))
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
            buildPersistSet(loadedImage) match {
              case Right(toPersist) =>
                updateRequestState(LibraryImageRequestStates.PERSISTING)
                uploadAndPersistImages(loadedImage, toPersist, libraryId, source, overwriteExistingImage)
              case Left(failure) =>
                Future.successful(failure)
            }
          } else {
            // have existing LibraryImages, use those
            log.info(s"[kic] Existing stored images found: $existingSameHash")
            Future.successful(copyExistingImagesAndReplace(libraryId, source, existingSameHash))
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def buildPersistSet(sourceImage: LibraryImageProcessState.LibraryImageLoadedAndHashed): Either[LibraryImageStoreFailure, Set[LibraryImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    def keygen(width: Int, height: Int, label: String = "") = {
      "library/" + sourceImage.hash.hash + "_" + width + "x" + height + label + "." + outFormat.value
    }
    validateAndLoadImageFile(sourceImage.file.file) match {
      case Success(image) =>
        val resizedImages = calcSizesForImage(image).map { boundingBox =>
          log.info(s"[kic] Using bounding box $boundingBox px")
          photoshop.resizeImage(image, sourceImage.format, boundingBox).map { resizedImage =>
            bufferedImageToInputStream(resizedImage, outFormat).map {
              case (is, bytes) =>
                val key = keygen(resizedImage.getWidth, resizedImage.getHeight)
                LibraryImageProcessState.ReadyToPersist(key, outFormat, is, resizedImage, bytes)
            }
          }.flatten match {
            case Success(img) => Right(img)
            case Failure(ex) => Left(LibraryImageProcessState.InvalidLibraryImage(ex))
          }
        }

        resizedImages.find(_.isLeft) match {
          case Some(error) => // failure of at least one of the images
            Left(error.left.get)
          case None =>
            val original = bufferedImageToInputStream(image, inputFormatToOutputFormat(sourceImage.format)).map {
              case (is, bytes) =>
                val key = keygen(image.getWidth, image.getHeight, originalLabel)
                LibraryImageProcessState.ReadyToPersist(key, outFormat, is, image, bytes)
            }
            original match {
              case Success(o) =>
                val resizedSet = resizedImages.collect { case Right(img) => img }
                Right(resizedSet + o)
              case Failure(ex) => Left(LibraryImageProcessState.InvalidLibraryImage(ex))
            }
        }
      case Failure(ex) =>
        Left(LibraryImageProcessState.InvalidLibraryImage(ex))
    }
  }

  private def uploadAndPersistImages(originalImage: LibraryImageProcessState.LibraryImageLoadedAndHashed, toPersist: Set[LibraryImageProcessState.ReadyToPersist], libraryId: Id[Library], source: LibraryImageSource, overwriteExistingImage: Boolean): Future[LibraryImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[kic] Persisting ${image.key} (${image.bytes} B)")
      libraryImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        LibraryImageProcessState.UploadedLibraryImage(image.key, image.format, image.image)
      }
    }

    Future.sequence(uploads).map { results =>
      val libraryImages = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.key.takeRight(7).indexOf(originalLabel) != -1
          val libImg = LibraryImage(libraryId = libraryId, imagePath = uploadedImage.key, format = uploadedImage.format,
            width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, offsetWidth = 0, offsetHeight = 0, source = source, sourceImageUrl = originalImage.sourceImageUrl,
            sourceFileHash = originalImage.hash, isOriginal = isOriginal)
          uploadedImage.image.flush()
          libImg
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImagesForLibrary = libraryImageRepo.getForLibraryId(libraryId).toSet
        replaceOldLibraryImagesWithNew(existingImagesForLibrary, libraryImages)
      }
      LibraryImageProcessState.StoreSuccess
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist library image", ex)
        LibraryImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        LibraryImageProcessState.CDNUploadFailed(ex)
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

  private def copyExistingImagesAndReplace(libraryId: Id[Library], source: LibraryImageSource, existingSameHash: Seq[LibraryImage]) = {
    val allForThisLibrary = existingSameHash.filter(i => i.libraryId == libraryId)
    val activeForThisLibrary = allForThisLibrary.filter(i => i.state == LibraryImageStates.ACTIVE)
    if (activeForThisLibrary.nonEmpty) {
      LibraryImageProcessState.ExistingStoredImagesFound(activeForThisLibrary)
    } else if (allForThisLibrary.nonEmpty) {
      val saved = db.readWrite { implicit session =>
        allForThisLibrary.map(_.copy(state = LibraryImageStates.ACTIVE)).map { img => libraryImageRepo.save(img) }
      }
      LibraryImageProcessState.ExistingStoredImagesFound(saved)
    } else {
      val copiedImages = existingSameHash.map { prev =>
        LibraryImage(state = LibraryImageStates.ACTIVE, libraryId = libraryId, imagePath = prev.imagePath, format = prev.format,
          width = prev.width, height = prev.height, offsetWidth = prev.offsetWidth, offsetHeight = prev.offsetHeight, source = source,
          sourceFileHash = prev.sourceFileHash, sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal)
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
      LibraryImageProcessState.ExistingStoredImagesFound(saved)
    }
  }

  private def updateRequestState(state: State[LibraryImageRequest])(implicit requestIdOpt: Option[Id[LibraryImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        libraryImageRequestRepo.updateState(requestId, state)
      }
    }
  }

  private def finalizeImageRequestState(libraryId: Id[Library], requestIdOpt: Option[Id[LibraryImageRequest]], doneResult: LibraryImageProcessDone): Unit = {
    import com.keepit.commanders.LibraryImageProcessState._
    import com.keepit.model.LibraryImageRequestStates._

    requestIdOpt.map { requestId =>
      val (state, failureCode, failureReason) = doneResult match {
        case err: UpstreamProviderFailed =>
          (UPSTREAM_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case UpstreamProviderNoLibraryImage$ =>
          (UPSTREAM_FAILED, Some(UpstreamProviderNoLibraryImage$.reason), None)
        case err: SourceFetchFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: HashFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: InvalidLibraryImage =>
          (PROCESSING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: DbPersistFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: CDNUploadFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case success: LibraryImageProcessSuccess =>
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

sealed trait LibraryImageProcessState
sealed trait LibraryImageProcessDone extends LibraryImageProcessState
sealed trait LibraryImageProcessSuccess extends LibraryImageProcessDone
sealed trait LibraryImageStoreInProgress extends LibraryImageProcessState
sealed abstract class LibraryImageStoreFailure(val reason: String) extends LibraryImageProcessState with LibraryImageProcessDone
sealed abstract class LibraryImageStoreFailureWithException(ex: Throwable, reason: String) extends LibraryImageStoreFailure(reason)
object LibraryImageProcessState {
  // In-progress
  case class LibraryImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends LibraryImageStoreInProgress
  case class LibraryImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash) extends LibraryImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int) extends LibraryImageStoreInProgress
  case class UploadedLibraryImage(key: String, format: ImageFormat, image: BufferedImage) extends LibraryImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoLibraryImage$ extends LibraryImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidLibraryImage(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends LibraryImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case object StoreSuccess extends LibraryImageProcessState with LibraryImageProcessSuccess
  case class ExistingStoredImagesFound(images: Seq[LibraryImage]) extends LibraryImageProcessState with LibraryImageProcessSuccess
}