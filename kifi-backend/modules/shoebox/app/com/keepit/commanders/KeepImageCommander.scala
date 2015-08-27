package com.keepit.commanders

import java.io.{ FileInputStream, InputStream }
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
import com.keepit.model.ImageProcessState.{ ImageLoadedAndHashed, ReadyToPersist }
import com.keepit.model.ProcessImageOperation.Original
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.EmbedlyArticle
import com.keepit.rover.model.{ RoverUriSummary, BasicImage, BasicImages }
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

object KeepImageSizes {
  val scaleSizes = ScaledImageSize.allSizes
  val cropSizes = Seq(CroppedImageSize.Small)
}

@ImplementedBy(classOf[KeepImageCommanderImpl])
trait KeepImageCommander {

  def getUrl(keepImage: KeepImage): String
  def getBestImageForKeep(keepId: Id[Keep], imageRequest: ProcessImageRequest): Option[Option[KeepImage]]
  def getBestImagesForKeeps(keepIds: Set[Id[Keep]], imageRequest: ProcessImageRequest): Map[Id[Keep], Option[KeepImage]]
  def getBestImagesForKeepsPatiently(keepIds: Set[Id[Keep]], imageRequest: ProcessImageRequest): Future[Map[Id[Keep], Option[KeepImage]]]
  def getBasicImagesForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], BasicImages]

  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean = true, overwriteExistingChoice: Boolean = false): Future[ImageProcessDone]
  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: ImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[ImageProcessDone]
  def setKeepImageFromFile(image: File, keepId: Id[Keep], source: ImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[ImageProcessDone]

  // Returns true if images were removed, false otherwise
  def removeKeepImageForKeep(keepId: Id[Keep]): Boolean
}

@Singleton
class KeepImageCommanderImpl @Inject() (
    imageStore: RoverImageStore,
    db: Database,
    keepRepo: KeepRepo,
    rover: RoverServiceClient,
    implicit val s3ImageConfig: S3ImageConfig,
    normalizedUriRepo: NormalizedURIRepo,
    keepImageRequestRepo: KeepImageRequestRepo,
    airbrake: AirbrakeNotifier,
    keepImageRepo: KeepImageRepo,
    implicit val photoshop: Photoshop,
    implicit val defaultContext: ExecutionContext,
    val webService: WebService) extends KeepImageCommander with ProcessedImageHelper with Logging {

  def getUrl(keepImage: KeepImage): String = keepImage.imagePath.getUrl

  def getBestImageForKeep(keepId: Id[Keep], imageRequest: ProcessImageRequest): Option[Option[KeepImage]] = {
    getBestImagesForKeeps(Set(keepId), imageRequest).get(keepId)
  }

  def getBestImagesForKeeps(keepIds: Set[Id[Keep]], imageRequest: ProcessImageRequest): Map[Id[Keep], Option[KeepImage]] = {
    val strictAspectRatio = imageRequest.operation == ProcessImageOperation.CenteredCrop
    val idealImageSize = imageRequest.size
    getAllImagesForKeeps(keepIds).mapValues(ProcessedImageSize.pickBestImage(idealImageSize, _, strictAspectRatio))
  }

  def getBasicImagesForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], BasicImages] = {
    getAllImagesForKeeps(keepIds).mapValues { keepImages =>
      val images = keepImages.map(BasicImage.fromBaseImage)
      BasicImages(images.toSet)
    }
  }

  private def getAllImagesForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], Seq[KeepImage]] = {
    if (keepIds.isEmpty) {
      Map.empty[Id[Keep], Seq[KeepImage]]
    } else {
      val allImagesByKeepId = db.readOnlyReplica { implicit session => keepImageRepo.getAllForKeepIds(keepIds) }.groupBy(_.keepId)
      (keepIds -- allImagesByKeepId.keys).foreach { missingKeepId =>
        SafeFuture { autoSetKeepImage(missingKeepId, localOnly = false, overwriteExistingChoice = false) }
      }
      allImagesByKeepId.mapValues { keepImages =>
        keepImages.filter(_.state == KeepImageStates.ACTIVE) // a missing set (keep image not set) is not equivalent to an empty set (keep image set to none)
      }
    }
  }

  def getBestImagesForKeepsPatiently(keepIds: Set[Id[Keep]], imageRequest: ProcessImageRequest): Future[Map[Id[Keep], Option[KeepImage]]] = {
    if (keepIds.isEmpty) {
      Future.successful(Map.empty[Id[Keep], Option[KeepImage]])
    } else {
      val allImagesByKeepId = db.readOnlyReplica { implicit session => keepImageRepo.getAllForKeepIds(keepIds) }.groupBy(_.keepId)

      Future.sequence(allImagesByKeepId.map {
        case (keepId, keepImages) =>
          val existingVersions = keepImages.filterNot(_.isOriginal).map(KeepImage.toProcessImageRequest).toSet
          val originalImageOpt = keepImages.find(_.isOriginal)

          val processDoneFOpt = originalImageOpt.map { originalImage =>

            val expectedVersions = calcSizesForImage(originalImage.imageSize, KeepImageSizes.scaleSizes, KeepImageSizes.cropSizes)
            val missingVersions = diffProcessImageRequests(expectedVersions, existingVersions)

            if (missingVersions.nonEmpty) {
              log.info(s"[getBestImagesForKeepsPatiently] keepId=$keepId has missing versions: $missingVersions; existing: $existingVersions; expected $expectedVersions")
              processMissingImagesForKeep(keepId, originalImage, missingVersions)
            } else Future.successful(())
          }.getOrElse(Future.successful(()))

          processDoneFOpt map { _ => keepId -> getBestImageForKeep(keepId, imageRequest).flatMap(identity) }
      }.toSeq).map(_.toMap)
    }
  }

  private val autoSetConsolidator = new RequestConsolidator[Id[Keep], ImageProcessDone](1.minute)
  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean, overwriteExistingChoice: Boolean): Future[ImageProcessDone] = {
    val keep = db.readOnlyMaster { implicit session =>
      keepRepo.get(keepId)
    }
    autoSetConsolidator(keepId) { keepId =>
      // todo(Léo): consider using rover.getOrElseFetchUriSummary if localOnly = false?
      val remoteImageF = rover.getImagesByUris(Set(keep.uriId)).imap(_.get(keep.uriId)).map {
        case Some(images) => images.getLargest.map(_.path.getUrl)
        case _ => None
      }

      remoteImageF.flatMap { remoteImageOpt =>
        remoteImageOpt.map { imageUrl =>
          log.info(s"[kic] Using $imageUrl")
          val realUrl = if (imageUrl.startsWith("//")) "http:" + imageUrl else imageUrl
          val imageSource = {
            if (RoverUriSummary.defaultProvider == EmbedlyArticle) ImageSource.Embedly
            else ImageSource.RoverArticle(RoverUriSummary.defaultProvider)
          }
          fetchAndSet(realUrl, keepId, imageSource, overwriteExistingImage = overwriteExistingChoice)(None)
        }.getOrElse {
          Future.successful(ImageProcessState.UpstreamProviderNoImage)
        }
      }.recover {
        case ex: Throwable =>
          ImageProcessState.UpstreamProviderFailed(ex)
      }
    }
  }

  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: ImageSource, requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(imageUrl, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }
  def setKeepImageFromFile(image: File, keepId: Id[Keep], source: ImageSource, requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(image, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }

  private val processMissingImagesConsolidator = new RequestConsolidator[Id[Keep], ImageProcessDone](1.minute)
  private def processMissingImagesForKeep(keepId: Id[Keep], originalImage: KeepImage,
    missingVersions: Set[ProcessImageRequest]): Future[ImageProcessDone] = processMissingImagesConsolidator(keepId) { _ =>

    val imageUri = {
      val url = getUrl(originalImage)
      if (url.startsWith("http")) url else "http:" + url
    }

    val imageLoadedAndHashedF = fetchAndHashRemoteImage(imageUri)
    imageLoadedAndHashedF.flatMap {
      case Right(orig) =>
        validateAndGetImageInfo(orig.file) match {
          case Success(imageInfo) =>
            val originalSize = ImageSize(imageInfo.width, imageInfo.height)
            val buildSet = processAndPersistImages(orig.file, KeepImage.label, originalImage.sourceFileHash, originalImage.format, missingVersions)(photoshop)

            buildSet match {
              case Right(toPersist) =>
                persistImageSet(keepId, originalImage.source, orig, originalSize, toPersist, overwriteExistingImage = true, amendExistingImages = true)(None) map {
                  case success: ImageProcessSuccess =>
                    log.info(s"[processMissingImagesForKeep] processed missing images for keepId=$keepId")
                    success
                  case err: ImageStoreFailure =>
                    airbrake.notify(s"[processMissingImagesForKeep] error in persistImageSet keepId=$keepId : ${err.reason}")
                    err
                }
              case Left(invalidImage) =>
                airbrake.notify(s"[processMissingImagesForKeep] error from processAndPersistImages keepId=$keepId", invalidImage.ex)
                Future.successful(invalidImage)
            }
          case Failure(e) =>
            airbrake.notify(s"[processMissingImagesForKeep] error validating original image keepId=$keepId", e)
            Future.successful(ImageProcessState.InvalidImage(e))
        }
      case Left(isf) =>
        airbrake.notify("[processMissingImagesForKeep] could not load original image: " + isf.reason)
        Future.successful(isf)
    }
  }

  private def exceptionToFailureReason(ex: Throwable) = {
    ex.getMessage + "\n\n" + ex.getStackTrace.collect {
      case t if t.getClassName.startsWith("com.keepit") =>
        t.getFileName + ":" + t.getLineNumber
    }.take(5).mkString("\n")
  }

  private def finalizeImageRequestState(keepId: Id[Keep], requestIdOpt: Option[Id[KeepImageRequest]], doneResult: ImageProcessDone): Unit = {
    import com.keepit.model.ImageProcessState._
    import com.keepit.model.KeepImageRequestStates._

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
        case BlacklistedImage =>
          (PROCESSING_FAILED, Some(BlacklistedImage.reason), None)
        case err: DbPersistFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case err: CDNUploadFailed =>
          (PERSISTING, Some(err.reason), Some(exceptionToFailureReason(err.ex)))
        case success: ImageProcessSuccess =>
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

  private def fetchAndSet(imageFile: File, keepId: Id[Keep], source: ImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }

  private def fetchAndSet(imageUrl: String, keepId: Id[Keep], source: ImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    detectUserPickedImageFromExistingHashAndReplace(imageUrl, keepId).map(Future.successful).getOrElse {
      runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashRemoteImage(imageUrl))
    }
  }

  private def runFetcherAndPersist(keepId: Id[Keep], source: ImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    val existingImagesForKeep = db.readOnlyMaster { implicit session =>
      keepImageRepo.getAllForKeepId(keepId)
    }
    if (existingImagesForKeep.nonEmpty && !overwriteExistingImage) {
      Future.successful(ImageProcessState.ExistingStoredImagesFound(existingImagesForKeep))
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
            val persistSet = buildPersistSet(loadedImage, KeepImage.label, KeepImageSizes.scaleSizes, KeepImageSizes.cropSizes)(photoshop)
            persistSet match {
              case Right(toPersist) =>
                val loadedImageSize = toPersist.find(_.processOperation == ProcessImageOperation.Original).map { toPersist =>
                  ImageSize(toPersist.imageInfo.width, toPersist.imageInfo.height)
                }.get
                persistImageSet(keepId, source, loadedImage, loadedImageSize, toPersist, overwriteExistingImage, amendExistingImages = false)
              case Left(e) => Future.successful(e)
            }
          } else {
            // have existing KeepImages, use those
            log.info(s"[kic] Existing stored images (${existingSameHash.size}) found: $existingSameHash")
            Future.successful(copyExistingImagesAndReplace(keepId, source, existingSameHash))
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def persistImageSet(keepId: Id[Keep], source: ImageSource, loadedImage: ImageLoadedAndHashed, loadedImageSize: ImageSize,
    toPersist: Set[ReadyToPersist], overwriteExistingImage: Boolean,
    amendExistingImages: Boolean)(implicit requestIdOpt: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    updateRequestState(KeepImageRequestStates.PERSISTING)
    uploadAndPersistImages(loadedImage, loadedImageSize, toPersist, keepId, source, overwriteExistingImage, amendExistingImages)
  }

  private case class ImageSourceIndex(hash: ImageHash, width: Int, height: Int, keepId: Id[Keep])
  private object ImageSourceIndex {
    def apply(image: KeepImage): ImageSourceIndex = ImageSourceIndex(image.sourceFileHash, image.width, image.height, image.keepId)
  }

  private def uniqueKeepImages(orig: Set[KeepImage]): Set[KeepImage] = {
    val uniqueMap = orig.map(i => ImageSourceIndex(i) -> i).toMap
    val uniqueKeeps = uniqueMap.values.toSet
    if (orig.size != uniqueKeeps.size) log.info(s"shrinking keep image set from ${orig.size} to ${uniqueKeeps.size}")
    uniqueKeeps
  }

  private def copyExistingImagesAndReplace(keepId: Id[Keep], source: ImageSource, existingSameHash: Seq[KeepImage]): ImageProcessDone = {
    val allForThisKeep = existingSameHash.filter(i => i.keepId == keepId)
    val activeForThisKeep = allForThisKeep.filter(i => i.state == KeepImageStates.ACTIVE)
    if (activeForThisKeep.nonEmpty) {
      ImageProcessState.ExistingStoredImagesFound(activeForThisKeep)
    } else if (allForThisKeep.nonEmpty) {
      val saved = db.readWrite { implicit session =>
        allForThisKeep.map(_.copy(state = KeepImageStates.ACTIVE)).map { img => keepImageRepo.save(img) }
      }
      ImageProcessState.ExistingStoredImagesFound(saved)
    } else {
      val copiedImagesSet: Set[KeepImage] = existingSameHash.toSet.flatMap { prev: KeepImage =>
        if (prev.keepId == keepId) {
          if (prev.state == KeepImageStates.ACTIVE) {
            log.info(s"skipping image since its already of the same keep id: $prev")
            None
          } else {
            log.info(s"re-activating image: $prev")
            Some(prev.copy(state = KeepImageStates.ACTIVE, source = source))
          }
        } else {
          val image = KeepImage(state = KeepImageStates.ACTIVE, keepId = keepId, imagePath = prev.imagePath, format = prev.format,
            width = prev.width, height = prev.height, source = source, sourceFileHash = prev.sourceFileHash,
            sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal, kind = prev.kind)
          Some(image)
        }
      }
      val uniqueImages = uniqueKeepImages(copiedImagesSet)
      if (existingSameHash.size != uniqueImages.size) log.info(s"smaller set of new images to keep for $keepId from ${existingSameHash.size} is ${uniqueImages.size}: $uniqueImages")

      val saved = db.readWrite(attempts = 3) { implicit session =>
        val existingForKeep = keepImageRepo.getForKeepId(keepId)
        existingForKeep.map { oldImg =>
          keepImageRepo.save(oldImg.copy(state = KeepImageStates.INACTIVE))
        }
        uniqueImages.map { img =>
          keepImageRepo.save(img)
        }
      }
      ImageProcessState.ExistingStoredImagesFound(saved.toSeq)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, loadedImageSize: ImageSize,
    toPersist: Set[ImageProcessState.ReadyToPersist], keepId: Id[Keep], source: ImageSource,
    overwriteExistingImage: Boolean, amendExistingImages: Boolean): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"[kic] Persisting ${image.key}")
      imageStore.put(image.key, image.file, imageFormatToMimeType(image.format)).map { r =>
        (ImageProcessState.UploadedImage(image.key, image.format, image.imageInfo, image.processOperation), image.file)
      }
    }

    Future.sequence(uploads).map { results =>
      val keepImages = results.map {
        case ((uploadedImage, _)) =>
          val isOriginal = uploadedImage.processOperation match {
            case Original => true
            case _ => false
          }

          val ki = KeepImage(keepId = keepId, imagePath = uploadedImage.key, format = uploadedImage.format, width = uploadedImage.imageInfo.width, height = uploadedImage.imageInfo.height, source = source, sourceImageUrl = originalImage.sourceImageUrl, sourceFileHash = originalImage.hash, isOriginal = isOriginal, kind = uploadedImage.processOperation)
          ki
      }
      db.readWrite(attempts = 3) { implicit session => // because of request consolidator, this can be very race-conditiony
        if (amendExistingImages) {
          amendKeepImages(keepImages)
        } else {
          val existingImagesForKeep = keepImageRepo.getAllForKeepId(keepId).toSet
          replaceOldKeepImagesWithNew(existingImagesForKeep, keepImages)
        }
      }
      val success = ImageProcessState.StoreSuccess(originalImage.format, loadedImageSize, originalImage.file.length.toInt)
      results.foreach(_._2.delete())
      success
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist keepimage", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def amendKeepImages(newKeepImages: Set[KeepImage])(implicit session: RWSession) = {
    log.info("[kic] Amending keep images keepId: " + newKeepImages.map(_.imagePath))
    newKeepImages.map(keepImageRepo.save)
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

  private def updateRequestState(state: State[KeepImageRequest])(implicit requestIdOpt: Option[Id[KeepImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        keepImageRequestRepo.updateState(requestId, state)
      }
    }
  }

  private val cdnUrl = s3ImageConfig.cdnBase.drop(s3ImageConfig.cdnBase.indexOf("//"))
  private val ourOwnImageUrl = s"(https?\\:)?$cdnUrl/keep/([0-9a-f]{32})_\\d+x\\d+.*".r
  private def detectUserPickedImageFromExistingHashAndReplace(imageUrl: String, keepId: Id[Keep]): Option[ImageProcessSuccess] = {
    Try {
      imageUrl match {
        case ourOwnImageUrl(_, hash) =>
          db.readWrite(attempts = 3) { implicit session =>
            val existingForHash = keepImageRepo.getBySourceHash(ImageHash(hash))
            if (existingForHash.nonEmpty) {
              copyExistingImagesAndReplace(keepId, ImageSource.UserPicked, existingForHash)
              val orig = existingForHash.filter(_.isOriginal).head
              Some(ImageProcessState.StoreSuccess(orig.format, orig.dimensions, 0))
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
