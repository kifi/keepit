package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io._
import java.net.URLConnection
import java.sql.SQLException

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.store._
import com.keepit.model._
import org.imgscalr.Scalr
import play.api.{ Mode, Play }
import play.api.Play.current
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import com.keepit.common.core._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepImageCommanderImpl])
trait KeepImageCommander {

  def getUrl(keepImage: KeepImage): String
  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[KeepImage]

  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean = true, overwriteExistingChoice: Boolean = false): Future[ImageProcessDone]
  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[ImageProcessDone]
  def setKeepImageFromFile(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]] = None): Future[ImageProcessDone]

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
    keepImageRepo: KeepImageRepo) extends KeepImageCommander with KeepImageHelper with Logging {

  def getUrl(keepImage: KeepImage): String = {
    s3ImageConfig.cdnBase + "/" + keepImage.imagePath
  }

  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[KeepImage] = {
    val keepImages = db.readOnlyReplica { implicit session =>
      keepImageRepo.getForKeepId(keepId)
    }
    KeepImageSize.pickBest(idealSize, keepImages)
  }

  private val autoSetConsolidator = new RequestConsolidator[Id[Keep], ImageProcessDone](1.minute)
  def autoSetKeepImage(keepId: Id[Keep], localOnly: Boolean, overwriteExistingChoice: Boolean): Future[ImageProcessDone] = {
    val keep = db.readOnlyMaster { implicit session =>
      keepRepo.get(keepId)
    }
    log.info(s"[kic] Autosetting for ${keep.id.get}: ${keep.url}")
    autoSetConsolidator(keepId) { keepId =>
      val localLookup = db.readOnlyMaster { implicit session =>
        imageInfoRepo.getLargestByUriWithPriority(keep.uriId).flatMap { imageInfo =>
          val nuri = normalizedUriRepo.get(keep.uriId)
          s3UriImageStore.getImageURL(imageInfo, nuri) tap { u => log.info(s"[kic] Got image URL $u") }
        }
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
          Future.successful(ImageProcessState.UpstreamProviderNoImage)
        }
      }.recover {
        case ex: Throwable =>
          ImageProcessState.UpstreamProviderFailed(ex)
      }
    }
  }

  def setKeepImageFromUrl(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(imageUrl, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }
  def setKeepImageFromFile(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    fetchAndSet(image, keepId, source, overwriteExistingImage = true)(requestId).map { done =>
      finalizeImageRequestState(keepId, requestId, done)
      done
    }
  }

  private def finalizeImageRequestState(keepId: Id[Keep], requestIdOpt: Option[Id[KeepImageRequest]], doneResult: ImageProcessDone): Unit = {
    import ImageProcessState._
    import KeepImageRequestStates._

    requestIdOpt.map { requestId =>
      val (state, failureCode, failureReason) = doneResult match {
        case err: UpstreamProviderFailed =>
          (UPSTREAM_FAILED, Some(err.reason), Some(err.ex.getMessage))
        case UpstreamProviderNoImage =>
          (UPSTREAM_FAILED, Some(UpstreamProviderNoImage.reason), None)
        case err: SourceFetchFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(err.ex.getMessage))
        case err: HashFailed =>
          (FETCHING_FAILED, Some(err.reason), Some(err.ex.getMessage))
        case err: InvalidImage =>
          (PROCESSING_FAILED, Some(err.reason), Some(err.ex.getMessage))
        case err: DbPersistFailed =>
          (PERSISTING, Some(err.reason), Some(err.ex.getMessage))
        case err: CDNUploadFailed =>
          (PERSISTING, Some(err.reason), Some(err.ex.getMessage))
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

  val originalLabel: String = "_o"

  private def fetchAndSet(imageFile: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }

  private def fetchAndSet(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashRemoteImage(imageUrl))
  }

  private def runFetcherAndPersist(keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]])(implicit requestId: Option[Id[KeepImageRequest]]): Future[ImageProcessDone] = {
    val existingImagesForKeep = db.readOnlyMaster { implicit session =>
      keepImageRepo.getForKeepId(keepId)
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
            Future.successful(copyExistingImages(keepId, source, existingSameHash))
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def copyExistingImages(keepId: Id[Keep], source: KeepImageSource, existingSameHash: Seq[KeepImage]) = {
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
      val copiedImages = existingSameHash.map { prev =>
        KeepImage(state = KeepImageStates.ACTIVE, keepId = keepId, imagePath = prev.imagePath, format = prev.format, width = prev.width, height = prev.height, source = source, sourceFileHash = prev.sourceFileHash, sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal)
      }
      val saved = db.readWrite { implicit session =>
        copiedImages.map { img =>
          keepImageRepo.save(img)
        }
      }
      ImageProcessState.ExistingStoredImagesFound(saved)
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      println(s"[kic] Persisting ${image.key} (${image.bytes} B)")
      keepImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image)
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
      val existingImagesForKeep = db.readOnlyMaster { implicit session =>
        keepImageRepo.getAllForKeepId(keepId)
      }
      db.readWrite { implicit session =>
        if (existingImagesForKeep.isEmpty) {
          println("0:\n\n" + keepImageRepo.all().mkString("\n"))
          keepImages.map { keepImage =>
            println("xx: " + keepImage)
            keepImageRepo.save(keepImage)
          }
        } else {
          println("1: " + existingImagesForKeep.toString())
          val (shouldBeActive, shouldBeInactive) = existingImagesForKeep.partition { existingImg =>
            keepImages.find { newImg =>
              existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
            }.nonEmpty
          }
          println("2: " + shouldBeActive.toString())
          println("3: " + shouldBeInactive.toString())
          val toActivate = shouldBeActive.filter(_.state != KeepImageStates.ACTIVE).map(_.copy(state = KeepImageStates.ACTIVE))
          val toDeactivate = shouldBeInactive.filter(_.state != KeepImageStates.INACTIVE).map(_.copy(state = KeepImageStates.INACTIVE))
          val toCreate = keepImages.filter { newImg =>
            existingImagesForKeep.find { existingImg =>
              existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
            }.isEmpty
          }

          println("3: " + toActivate.toString())
          println("4: " + toDeactivate.toString())
          println("5: " + toCreate.toString())

          log.info("[kic] Activating:" + toActivate.map(_.imagePath) + "\nDeactivating:" + toDeactivate.map(_.imagePath) + "\nCreating:" + toCreate.map(_.imagePath))
          toDeactivate.foreach(keepImageRepo.save)
          (toActivate ++ toCreate).map(keepImageRepo.save)
        }
        ImageProcessState.StoreSuccess
      }
    }.recover {
      case ex: SQLException =>
        log.error("Could not persist keepimage", ex)
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def buildPersistSet(sourceImage: ImageProcessState.ImageLoadedAndHashed): Either[KeepImageStoreFailure, Set[ImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    def keygen(width: Int, height: Int, label: String = "") = {
      "keep/" + sourceImage.hash.hash + "_" + width + "x" + height + label + "." + outFormat.value
    }
    validateAndLoadImageFile(sourceImage.file.file) match {
      case Success(image) =>
        val resizedImages = calcSizesForImage(image).map { boundingBox =>
          log.info(s"[kic] Using bounding box $boundingBox px")
          resizeImage(image, boundingBox).map { resizedImage =>
            bufferedImageToInputStream(resizedImage, outFormat).map {
              case (is, bytes) =>
                val key = keygen(resizedImage.getWidth, resizedImage.getHeight)
                ImageProcessState.ReadyToPersist(key, outFormat, is, resizedImage, bytes)
            }
          }.flatten match {
            case Success(img) => Right(img)
            case Failure(ex) => Left(ImageProcessState.InvalidImage(ex))
          }
        }

        resizedImages.find(_.isLeft) match {
          case Some(error) => // failure of at least one of the images
            Left(error.left.get)
          case None =>
            val original = bufferedImageToInputStream(image, inputFormatToOutputFormat(sourceImage.format)).map {
              case (is, bytes) =>
                val key = keygen(image.getWidth, image.getHeight, originalLabel)
                ImageProcessState.ReadyToPersist(key, outFormat, is, image, bytes)
            }
            original match {
              case Success(o) =>
                val resizedSet = resizedImages.collect { case Right(img) => img }
                Right(resizedSet + o)
              case Failure(ex) => Left(ImageProcessState.InvalidImage(ex))
            }
        }
      case Failure(ex) =>
        Left(ImageProcessState.InvalidImage(ex))
    }
  }

  private def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching ${file.file.getAbsolutePath}")

    val is = new BufferedInputStream(new FileInputStream(file.file))
    val formatOpt = Option(URLConnection.guessContentTypeFromStream(is)).flatMap { mimeType =>
      mimeTypeToImageFormat(mimeType)
    }.orElse {
      imageFilenameToFormat(file.file.getName)
    }
    is.close()

    formatOpt match {
      case Some(format) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Future.successful(Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(ImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(ImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  private def fetchAndHashRemoteImage(imageUrl: String): Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching $imageUrl")
    fetchRemoteImage(imageUrl).map {
      case (format, file) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
          case Failure(ex) =>
            Left(ImageProcessState.HashFailed(ex))
        }
    }.recover {
      case ex: Throwable =>
        Left(ImageProcessState.SourceFetchFailed(ex))
    }
  }

  private val remoteFetchConsolidater = new RequestConsolidator[String, (ImageFormat, TemporaryFile)](2.minutes)

  private def fetchRemoteImage(imageUrl: String, timeoutMs: Int = 20000): Future[(ImageFormat, TemporaryFile)] = {
    remoteFetchConsolidater(imageUrl) { imageUrl =>
      WS.url(imageUrl).withRequestTimeout(20000).getStream().flatMap {
        case (headers, streamBody) =>
          val formatOpt = headers.headers.get("Content-Type").flatMap(_.headOption)
            .flatMap(mimeTypeToImageFormat).orElse {
              imageFilenameToFormat(imageUrl.substring(imageUrl.lastIndexOf(".") + 1))
            }

          if (headers.status != 200) {
            Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}"))
          } else if (formatOpt.isEmpty) {
            Future.failed(new RuntimeException(s"Unknown image type, ${headers.headers.get("Content-Type")}"))
          } else {
            val tempFile = TemporaryFile(prefix = "remote-file", suffix = "." + formatOpt.get.value)
            tempFile.file.deleteOnExit()
            val outputStream = new FileOutputStream(tempFile.file)

            val maxSize = 1024 * 1024 * 16

            var len = 0
            val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
              len += bytes.length
              if (len > maxSize) { // max original size
                throw new Exception(s"Original image too large (> $len bytes): $imageUrl")
              } else {
                outputStream.write(bytes)
              }
            }

            streamBody.run(iteratee).andThen {
              case result =>
                outputStream.close()
                result.get
            }.map(_ => (formatOpt.get, tempFile))
          }
      }
    }

  }

  private def resizeImage(image: BufferedImage, boundingBox: Int): Try[BufferedImage] = Try {
    val img = Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, boundingBox)
    log.info(s"[kic] Bounding box $boundingBox resized to ${img.getHeight} x ${img.getWidth}")
    img
  }

  private def updateRequestState(state: State[KeepImageRequest])(implicit requestIdOpt: Option[Id[KeepImageRequest]]): Unit = {
    requestIdOpt.map { requestId =>
      db.readWrite { implicit session =>
        keepImageRequestRepo.updateState(requestId, state)
      }
    }
  }

}

