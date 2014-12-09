package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.sql.SQLException

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.store._
import com.keepit.model._
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepImageCommanderImpl])
trait KeepImageCommander {

  def getUrl(keepImage: KeepImage): String
  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[Option[KeepImage]]
  def getBestImagesForKeeps(keepIds: Set[Id[Keep]], idealSize: ImageSize): Map[Id[Keep], Option[KeepImage]]
  def getExistingImageUrlForKeepUri(nUriId: Id[NormalizedURI])(implicit session: RSession): Option[String]

  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean = true, overwriteExistingChoice: Boolean = false): Future[KeepImageProcessDone]
  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[KeepImageProcessDone]
  def setKeepImageFromFile(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[KeepImageProcessDone]

  // Returns true if images were removed, false otherwise
  def removeKeepImageForKeep(keepId: Id[Keep]): Boolean

}

@Singleton
class KeepImageCommanderImpl @Inject() (
    keepImageStore: KeepImageStore,
    db: Database,
    keepRepo: KeepRepo,
    uriSummaryCommander: URISummaryCommander,
    imageInfoRepo: ImageInfoRepo,
    s3UriImageStore: S3URIImageStore,
    s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    keepImageRequestRepo: KeepImageRequestRepo,
    airbrake: AirbrakeNotifier,
    keepImageRepo: KeepImageRepo,
    photoshop: Photoshop,
    val webService: WebService) extends KeepImageCommander with ProcessedImageHelper with Logging {

  def getUrl(keepImage: KeepImage): String = {
    s3ImageConfig.cdnBase + "/" + keepImage.imagePath
  }

  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[Option[KeepImage]] = {
    val keepImages = db.readOnlyReplica { implicit session =>
      keepImageRepo.getAllForKeepId(keepId)
    }
    if (keepImages.isEmpty) {
      SafeFuture { autoSetKeepImage(keepId, localOnly = false, overwriteExistingChoice = false) }
      None
    } else Some {
      val validKeepImages = keepImages.filter(_.state == KeepImageStates.ACTIVE)
      ProcessedImageSize.pickBestKeepImage(idealSize, validKeepImages)
    }
  }

  def getBestImagesForKeeps(keepIds: Set[Id[Keep]], idealSize: ImageSize): Map[Id[Keep], Option[KeepImage]] = {
    if (keepIds.isEmpty) {
      Map.empty[Id[Keep], Option[KeepImage]]
    } else {
      val allImagesByKeepId = db.readOnlyReplica { implicit session => keepImageRepo.getAllForKeepIds(keepIds) }.groupBy(_.keepId)
      (keepIds -- allImagesByKeepId.keys).foreach { missingKeepId =>
        SafeFuture { autoSetKeepImage(missingKeepId, localOnly = false, overwriteExistingChoice = false) }
      }
      allImagesByKeepId.mapValues { keepImages =>
        val validKeepImages = keepImages.filter(_.state == KeepImageStates.ACTIVE)
        ProcessedImageSize.pickBestKeepImage(idealSize, validKeepImages)
      }
    }
  }

  def getExistingImageUrlForKeepUri(nUriId: Id[NormalizedURI])(implicit session: RSession): Option[String] = {
    imageInfoRepo.getLargestByUriWithPriority(nUriId).flatMap { imageInfo =>
      val nuri = normalizedUriRepo.get(nUriId)
      s3UriImageStore.getImageURL(imageInfo, nuri)
    }
  }

  private val autoSetConsolidator = new RequestConsolidator[Id[Keep], KeepImageProcessDone](1.minute)
  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean, overwriteExistingChoice: Boolean): Future[KeepImageProcessDone] = {
    val keep = db.readOnlyMaster { implicit session =>
      keepRepo.get(keepId)
    }
    log.info(s"[kic] Autosetting for ${keep.id.get}: ${keep.url}")
    autoSetConsolidator(keepId) { keepId =>
      val localLookup = db.readOnlyMaster { implicit session =>
        getExistingImageUrlForKeepUri(keep.uriId)
      }
      val remoteImageF = if (localOnly) {
        Future.successful(localLookup)
      } else {
        localLookup.map(v => Future.successful(Some(v))).getOrElse {
          uriSummaryCommander.getDefaultURISummary(keep.uriId, waiting = true).map { summary =>
            summary.imageUrl
          }
        }
      }

      remoteImageF.flatMap { remoteImageOpt =>
        remoteImageOpt.map { imageUrl =>
          log.info(s"[kic] Using $imageUrl")
          val realUrl = if (imageUrl.startsWith("//")) "http:" + imageUrl else imageUrl
          fetchAndSet(realUrl, keepId, KeepImageSource.EmbedlyOrPagePeeker, overwriteExistingImage = overwriteExistingChoice)(None)
        }.getOrElse {
          Future.successful(KeepImageProcessState.UpstreamProviderNoKeepImage$)
        }
      }.recover {
        case ex: Throwable =>
          KeepImageProcessState.UpstreamProviderFailed(ex)
      }
    }
  }

  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]]): Future[KeepImageProcessDone] = {
    fetchAndSet(imageUrl, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }
  def setKeepImageFromFile(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]]): Future[KeepImageProcessDone] = {
    fetchAndSet(image, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }

  private def exceptionToFailureReason(ex: Throwable) = {
    ex.getMessage + "\n\n" + ex.getStackTrace.collect {
      case t if t.getClassName.startsWith("com.keepit") =>
        t.getFileName + ":" + t.getLineNumber
    }.take(5).mkString("\n")
  }

  private def finalizeImageRequestState(keepId: Id[Keep], requestIdOpt: Option[Id[KeepImageRequest]], doneResult: KeepImageProcessDone): Unit = {
    import com.keepit.commanders.KeepImageProcessState._
    import com.keepit.model.KeepImageRequestStates._

    requestIdOpt.map { requestId =>
      val (state, failureCode, failureReason) = doneResult match {
        case err: UpstreamProviderFailed =>
          (UPSTREAM_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case UpstreamProviderNoKeepImage$ =>
          (UPSTREAM_FAILED, Some(UpstreamProviderNoKeepImage$.reason), None)
        case err: SourceFetchFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: HashFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: InvalidKeepImage =>
          (PROCESSING_FAILED, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: DbPersistFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: CDNUploadFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case success: KeepImageProcessSuccess =>
          (INACTIVE, None, None)
      }

      db.readWrite { implicit session =>
        val request = keepImageRequestRepo.get(requestId)
        state match {
          case INACTIVE => // Success
            val keepImageOpt = keepImageRepo.getForKeepId(keepId).headOption
            keepImageRequestRepo.save(request.copy(state = state, successHash = keepImageOpt.map(_.sourceFileHash)))
          case failureState =>
            keepImageRequestRepo.save(request.copy(state = state, failureCode = failureCode, failureReason = failureReason))
        }
      }
    }
  }

  def removeKeepImageForKeep(keepId: Id[Keep]): Boolean = {
    val images = db.readWrite { implicit session =>
      keepImageRepo.getForKeepId(keepId).map { keepImage =>
        keepImageRepo.save(keepImage.copy(state = KeepImageStates.INACTIVE))
      }
    }
    images.nonEmpty
  }

  // Helper methods

  val originalLabel: String = "_o"

  private def fetchAndSet(imageFile: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[KeepImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }

  private def fetchAndSet(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[KeepImageProcessDone] = {
    detectUserPickedImageFromExistingHashAndReplace(imageUrl, keepId).map(Future.successful).getOrElse {
      runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashRemoteImage(imageUrl))
    }
  }

  private def runFetcherAndPersist(keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[KeepImageStoreFailure, KeepImageProcessState.KeepImageLoadedAndHashed]])(implicit requestId: Option[Id[KeepImageRequest]]): Future[KeepImageProcessDone] = {
    val existingImagesForKeep = db.readOnlyMaster { implicit session =>
      keepImageRepo.getAllForKeepId(keepId)
    }
    if (existingImagesForKeep.nonEmpty && !overwriteExistingImage) {
      Future.successful(KeepImageProcessState.ExistingStoredImagesFound(existingImagesForKeep))
    } else {
      updateRequestState(KeepImageRequestStates.FETCHING)

      fetcher.flatMap {
        case Right(loadedImage) =>
          updateRequestState(KeepImageRequestStates.PROCESSING)
          val existingSameHash = db.readOnlyReplica { implicit session =>
            keepImageRepo.getBySourceHash(loadedImage.hash)
          }
          if (existingSameHash.isEmpty || overwriteExistingImage) {
            // never seen this image, or we're reprocessing an image
            buildPersistSet(loadedImage) match {
              case Right(toPersist) =>
                updateRequestState(KeepImageRequestStates.PERSISTING)
                uploadAndPersistImages(loadedImage, toPersist, keepId, source, overwriteExistingImage)
              case Left(failure) =>
                Future.successful(failure)
            }
          } else {
            // have existing KeepImages, use those
            log.info(s"[kic] Existing stored images found: $existingSameHash")
            Future.successful(copyExistingImagesAndReplace(keepId, source, existingSameHash))
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def copyExistingImagesAndReplace(keepId: Id[Keep], source: KeepImageSource, existingSameHash: Seq[KeepImage]) = {
    val allForThisKeep = existingSameHash.filter(i => i.keepId == keepId)
    val activeForThisKeep = allForThisKeep.filter(i => i.state == KeepImageStates.ACTIVE)
    if (activeForThisKeep.nonEmpty) {
      KeepImageProcessState.ExistingStoredImagesFound(activeForThisKeep)
    } else if (allForThisKeep.nonEmpty) {
      val saved = db.readWrite { implicit session =>
        allForThisKeep.map(_.copy(state = KeepImageStates.ACTIVE)).map { img => keepImageRepo.save(img) }
      }
      KeepImageProcessState.ExistingStoredImagesFound(saved)
    } else {
      val copiedImages = existingSameHash.map { prev =>
        KeepImage(state = KeepImageStates.ACTIVE, keepId = keepId, imagePath = prev.imagePath, format = prev.format, width = prev.width, height = prev.height, source = source, sourceFileHash = prev.sourceFileHash, sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal)
      }

      val saved = db.readWrite { implicit session =>
        val existingForKeep = keepImageRepo.getForKeepId(keepId)
        existingForKeep.map { oldImg =>
          keepImageRepo.save(oldImg.copy(state = KeepImageStates.INACTIVE))
        }
        copiedImages.map { img =>
          keepImageRepo.save(img)
        }
      }
      KeepImageProcessState.ExistingStoredImagesFound(saved)
    }
  }

  private def uploadAndPersistImages(originalImage: KeepImageProcessState.KeepImageLoadedAndHashed, toPersist: Set[KeepImageProcessState.ReadyToPersist], keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean): Future[KeepImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[kic] Persisting ${image.key} (${image.bytes} B)")
      keepImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        KeepImageProcessState.UploadedKeepImage(image.key, image.format, image.image)
      }
    }

    Future.sequence(uploads).map { results =>
      val keepImages = results.map {
        case uploadedImage =>
          val isOriginal = uploadedImage.key.takeRight(7).indexOf(originalLabel) != -1
          val ki = KeepImage(keepId = keepId, imagePath = uploadedImage.key, format = uploadedImage.format, width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, source = source, sourceImageUrl = originalImage.sourceImageUrl, sourceFileHash = originalImage.hash, isOriginal = isOriginal)
          uploadedImage.image.flush()
          ki
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        val existingImagesForKeep = keepImageRepo.getAllForKeepId(keepId).toSet
        replaceOldKeepImagesWithNew(existingImagesForKeep, keepImages)
      }
      KeepImageProcessState.StoreSuccess
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist keepimage", ex)
        KeepImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        KeepImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def replaceOldKeepImagesWithNew(oldKeepImages: Set[KeepImage], newKeepImages: Set[KeepImage])(implicit session: RWSession) = {
    if (oldKeepImages.isEmpty) {
      newKeepImages.map(keepImageRepo.save)
    } else {
      val (shouldBeActive, shouldBeInactive) = oldKeepImages.partition { existingImg =>
        newKeepImages.find { newImg =>
          existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
        }.nonEmpty
      }
      val toActivate = shouldBeActive.filter(_.state != KeepImageStates.ACTIVE).map(_.copy(state = KeepImageStates.ACTIVE))
      val toDeactivate = shouldBeInactive.filter(_.state != KeepImageStates.INACTIVE).map(_.copy(state = KeepImageStates.INACTIVE))
      val toCreate = newKeepImages.filter { newImg =>
        oldKeepImages.find { existingImg =>
          existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
        }.isEmpty
      }

      log.info("[kic] Activating:" + toActivate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
      toDeactivate.foreach(keepImageRepo.save)
      (toActivate ++ toCreate).map(keepImageRepo.save)
    }
  }

  private def buildPersistSet(sourceImage: KeepImageProcessState.KeepImageLoadedAndHashed): Either[KeepImageStoreFailure, Set[KeepImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    def keygen(width: Int, height: Int, label: String = "") = {
      "keep/" + sourceImage.hash.hash + "_" + width + "x" + height + label + "." + outFormat.value
    }
    validateAndLoadImageFile(sourceImage.file.file) match {
      case Success(image) =>
        val resizedImages = calcSizesForImage(image).map { boundingBox =>
          log.info(s"[kic] Using bounding box $boundingBox px")
          photoshop.resizeImage(image, sourceImage.format, boundingBox).map { resizedImage =>
            bufferedImageToInputStream(resizedImage, outFormat).map {
              case (is, bytes) =>
                val key = keygen(resizedImage.getWidth, resizedImage.getHeight)
                KeepImageProcessState.ReadyToPersist(key, outFormat, is, resizedImage, bytes)
            }
          }.flatten match {
            case Success(img) => Right(img)
            case Failure(ex) => Left(KeepImageProcessState.InvalidKeepImage(ex))
          }
        }

        resizedImages.find(_.isLeft) match {
          case Some(error) => // failure of at least one of the images
            Left(error.left.get)
          case None =>
            val original = bufferedImageToInputStream(image, inputFormatToOutputFormat(sourceImage.format)).map {
              case (is, bytes) =>
                val key = keygen(image.getWidth, image.getHeight, originalLabel)
                KeepImageProcessState.ReadyToPersist(key, outFormat, is, image, bytes)
            }
            original match {
              case Success(o) =>
                val resizedSet = resizedImages.collect { case Right(img) => img }
                Right(resizedSet + o)
              case Failure(ex) => Left(KeepImageProcessState.InvalidKeepImage(ex))
            }
        }
      case Failure(ex) =>
        Left(KeepImageProcessState.InvalidKeepImage(ex))
    }
  }

  private def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[KeepImageStoreFailure, KeepImageProcessState.KeepImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching ${file.file.getAbsolutePath}")

    val formatOpt = detectImageType(file)

    formatOpt match {
      case Some(format) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Future.successful(Right(KeepImageProcessState.KeepImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(KeepImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(KeepImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  private def fetchAndHashRemoteImage(imageUrl: String): Future[Either[KeepImageStoreFailure, KeepImageProcessState.KeepImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching $imageUrl")
    fetchRemoteImage(imageUrl).map {
      case (format, file) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Right(KeepImageProcessState.KeepImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
          case Failure(ex) =>
            Left(KeepImageProcessState.HashFailed(ex))
        }
    }.recover {
      case ex: Throwable =>
        Left(KeepImageProcessState.SourceFetchFailed(ex))
    }
  }

  private def updateRequestState(state: State[KeepImageRequest])(implicit requestIdOpt: Option[Id[KeepImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        keepImageRequestRepo.updateState(requestId, state)
      }
    }
  }

  private val cdnUrl = s3ImageConfig.cdnBase.drop(s3ImageConfig.cdnBase.indexOf("//"))
  private val ourOwnImageUrl = s"(https?\\:)?$cdnUrl/keep/([0-9a-f]{32})_\\d+x\\d+.*".r
  private def detectUserPickedImageFromExistingHashAndReplace(imageUrl: String, keepId: Id[Keep]): Option[KeepImageProcessSuccess] = {
    Try {
      imageUrl match {
        case ourOwnImageUrl(_, hash) =>
          db.readWrite(attempts = 3) { implicit session =>
            val existingForHash = keepImageRepo.getBySourceHash(ImageHash(hash))
            if (existingForHash.nonEmpty) {
              copyExistingImagesAndReplace(keepId, KeepImageSource.UserPicked, existingForHash)
              Some(KeepImageProcessState.StoreSuccess)
            } else {
              None
            }
          }
        case _ =>
          None
      }
    }.recover {
      case ex: Throwable =>
        airbrake.notify(s"Could not see if we have an existing version of $imageUrl", ex)
        None
    }.toOption.flatten
  }

}

sealed trait KeepImageProcessState
sealed trait KeepImageProcessDone extends KeepImageProcessState
sealed trait KeepImageProcessSuccess extends KeepImageProcessDone
sealed trait KeepImageStoreInProgress extends KeepImageProcessState
sealed abstract class KeepImageStoreFailure(val reason: String) extends KeepImageProcessState with KeepImageProcessDone
sealed abstract class KeepImageStoreFailureWithException(ex: Throwable, reason: String) extends KeepImageStoreFailure(reason)
object KeepImageProcessState {
  // In-progress
  case class KeepImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends KeepImageStoreInProgress
  case class KeepImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash) extends KeepImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int) extends KeepImageStoreInProgress
  case class UploadedKeepImage(key: String, format: ImageFormat, image: BufferedImage) extends KeepImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoKeepImage$ extends KeepImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidKeepImage(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case object StoreSuccess extends KeepImageProcessState with KeepImageProcessSuccess
  case class ExistingStoredImagesFound(images: Seq[KeepImage]) extends KeepImageProcessState with KeepImageProcessSuccess
}

