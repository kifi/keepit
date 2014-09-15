package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io._
import java.math.BigInteger
import java.net.URLConnection
import java.security.MessageDigest
import java.sql.SQLException
import javax.imageio.ImageIO

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.store._
import com.keepit.model._
import org.imgscalr.Scalr
import play.api.Play.current
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import com.keepit.common.core._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepImageCommanderImpl])
trait KeepImageCommander {

  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[KeepImage]

  def autoSetKeepImage(keepId: Id[Keep], overwriteExistingChoice: Boolean = false): Future[ImageProcessDone]
  def setKeepImage(imageUrl: String, keepId: Id[Keep], source: KeepImageSource): Future[ImageProcessDone]
  def setKeepImage(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource): Future[ImageProcessDone]

  // Returns true if images were removed, false otherwise
  def removeKeepImageForKeep(keepId: Id[Keep]): Boolean

}

class KeepImageCommanderImpl @Inject() (
    keepImageStore: KeepImageStore,
    db: Database,
    keepRepo: KeepRepo,
    uriSummaryCommander: URISummaryCommander,
    keepImageRepo: KeepImageRepo) extends KeepImageCommander with Logging {

  def getBestImageForKeep(keepId: Id[Keep], idealSize: ImageSize): Option[KeepImage] = {
    val keepImages = db.readOnlyReplica { implicit session =>
      keepImageRepo.getForKeepId(keepId)
    }
    KeepImageSize.pickBest(idealSize, keepImages)
  }

  def autoSetKeepImage(keepId: Id[Keep], overwriteExistingChoice: Boolean): Future[ImageProcessDone] = {
    val keep = db.readOnlyMaster { implicit session =>
      keepRepo.get(keepId)
    }
    uriSummaryCommander.getDefaultURISummary(keep.uriId, waiting = true).flatMap { summary =>
      log.info(summary.toString)
      summary.imageUrl.map { imageUrl =>
        fetchAndSet(imageUrl, keepId, KeepImageSource.EmbedlyOrPagePeeker, overwriteExistingImage = overwriteExistingChoice)
      }.getOrElse {
        Future.successful(ImageProcessState.UpstreamProviderNoImage)
      }
    }.recover {
      case ex: Throwable =>
        ImageProcessState.UpstreamProviderFailed(ex)
    }
  }

  def setKeepImage(imageUrl: String, keepId: Id[Keep], source: KeepImageSource): Future[ImageProcessDone] = {
    fetchAndSet(imageUrl, keepId, source, overwriteExistingImage = true)
  }
  def setKeepImage(image: TemporaryFile, keepId: Id[Keep], source: KeepImageSource): Future[ImageProcessDone] = {
    fetchAndSet(image, keepId, source, overwriteExistingImage = true)
  }

  def removeKeepImageForKeep(keepId: Id[Keep]): Boolean = {
    val images = db.readWrite { implicit session =>
      keepImageRepo.getForKeepId(keepId).map { keepImage =>
        keepImageRepo.save(keepImage.copy(state = KeepImageStates.INACTIVE))
      }
    }
    images.nonEmpty
  }

  // todo
  // Track if user picked image or system did
  // Use data from user picks to do a better system pick

  // try ULTRA_QUALITY

  // Helper methods

  private def fetchAndSet(imageFile: TemporaryFile, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean): Future[ImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashLocalImage(imageFile))
  }

  private def fetchAndSet(imageUrl: String, keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean): Future[ImageProcessDone] = {
    runFetcherAndPersist(keepId, source, overwriteExistingImage)(fetchAndHashRemoteImage(imageUrl))
  }

  private def runFetcherAndPersist(keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean)(fetcher: => Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]]): Future[ImageProcessDone] = {
    val existingImagesForKeep = db.readOnlyMaster { implicit session =>
      keepImageRepo.getForKeepId(keepId)
    }
    if (existingImagesForKeep.nonEmpty && !overwriteExistingImage) {
      Future.successful(ImageProcessState.ExistingStoredImagesFound(existingImagesForKeep))
    } else {
      fetcher.flatMap {
        case Right(loadedImage) =>
          val existingSameHash = db.readOnlyReplica { implicit session =>
            keepImageRepo.getBySourceHash(loadedImage.hash)
          }
          if (existingSameHash.isEmpty || overwriteExistingImage) {
            // never seen this image, or we're reprocessing an image
            buildPersistSet(loadedImage) match {
              case Right(toPersist) =>
                uploadAndPersistImages(loadedImage, toPersist, keepId, source, overwriteExistingImage)
              case Left(failure) =>
                Future.successful(failure)
            }
          } else {
            // have existing KeepImages, use those
            log.info(s"Existing stored images found: $existingSameHash")
            val allForThisKeep = existingSameHash.filter(i => i.keepId == keepId)
            val activeForThisKeep = allForThisKeep.filter(i => i.state == KeepImageStates.ACTIVE)
            if (activeForThisKeep.nonEmpty) {
              Future.successful(ImageProcessState.ExistingStoredImagesFound(activeForThisKeep))
            } else if (allForThisKeep.nonEmpty) {
              val saved = db.readWrite { implicit session =>
                allForThisKeep.map(_.copy(state = KeepImageStates.ACTIVE)).map { img => keepImageRepo.save(img) }
              }
              Future.successful(ImageProcessState.ExistingStoredImagesFound(saved))
            } else {
              val copiedImages = existingSameHash.map { prev =>
                KeepImage(state = KeepImageStates.ACTIVE, keepId = keepId, imageUrl = prev.imageUrl, format = prev.format, width = prev.width, height = prev.height, source = source, sourceFileHash = prev.sourceFileHash, sourceImageUrl = prev.sourceImageUrl, isOriginal = prev.isOriginal)
              }
              val saved = db.readWrite { implicit session =>
                copiedImages.map { img =>
                  keepImageRepo.save(img)
                }
              }
              Future.successful(ImageProcessState.ExistingStoredImagesFound(saved))
            }
          }
        case Left(failure) => Future.successful(failure)
      }
    }
  }

  private def uploadAndPersistImages(originalImage: ImageProcessState.ImageLoadedAndHashed, toPersist: Set[ImageProcessState.ReadyToPersist], keepId: Id[Keep], source: KeepImageSource, overwriteExistingImage: Boolean): Future[ImageProcessDone] = {
    val uploads = toPersist.map { image =>
      log.info(s"Persisting ${image.key} (${image.bytes} B)")
      keepImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
        ImageProcessState.UploadedImage(image.key, image.format, image.image)
      }
    }
    Future.sequence(uploads).map { results =>
      val keepImages = results.map {
        case uploadedImage =>
          // todo isOriginal
          val ki = KeepImage(keepId = keepId, imageUrl = uploadedImage.key, format = uploadedImage.format, width = uploadedImage.image.getWidth, height = uploadedImage.image.getHeight, source = source, sourceImageUrl = originalImage.sourceImageUrl, sourceFileHash = originalImage.hash, isOriginal = false)
          uploadedImage.image.flush()
          ki
      }
      val existingImagesForKeep = db.readOnlyMaster { implicit session =>
        keepImageRepo.getAllForKeepId(keepId)
      }
      db.readWrite { implicit session =>
        if (existingImagesForKeep.isEmpty) {
          keepImages.map { keepImage =>
            keepImageRepo.save(keepImage)
          }
        } else {
          val (shouldBeActive, shouldBeInactive) = existingImagesForKeep.partition { existingImg =>
            keepImages.find { newImg =>
              existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
            }.nonEmpty
          }
          val toActivate = shouldBeActive.filter(_.state != KeepImageStates.ACTIVE).map(_.copy(state = KeepImageStates.ACTIVE))
          val toDeactivate = shouldBeInactive.filter(_.state != KeepImageStates.INACTIVE).map(_.copy(state = KeepImageStates.INACTIVE))
          val toCreate = keepImages.filter { newImg =>
            existingImagesForKeep.find { existingImg =>
              existingImg.sourceFileHash == newImg.sourceFileHash && existingImg.width == newImg.width && existingImg.height == newImg.height
            }.isEmpty
          }

          log.info("Activating:" + toActivate + "\nDeactivating:" + toDeactivate + "\nCreating:" + toCreate)
          toDeactivate.foreach(keepImageRepo.save)
          (toActivate ++ toCreate).map(keepImageRepo.save)
        }
        ImageProcessState.StoreSuccess
      }
    }.recover {
      case ex: SQLException =>
        ImageProcessState.DbPersistFailed(ex)
      case ex: Throwable =>
        ImageProcessState.CDNUploadFailed(ex)
    }
  }

  private def inputFormatToOutputFormat(format: ImageFormat): ImageFormat = {
    format match {
      case ImageFormat.JPG => ImageFormat.JPG
      case _ => ImageFormat.PNG
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
          log.info(s"Using bounding box $boundingBox px")
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
                val key = keygen(image.getWidth, image.getHeight, "_o")
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

  private def fetchAndHashRemoteImage(imageUrl: String): Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"Fetching $imageUrl")
    fetchRemoteImage(imageUrl).map {
      case (format, file) =>
        log.info(s"Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"Hashed: ${hash.hash}")
            Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
          case Failure(ex) =>
            Left(ImageProcessState.HashFailed(ex))
        }
    }.recover {
      case ex: Throwable =>
        Left(ImageProcessState.SourceFetchFailed(ex))
    }
  }

  private def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[KeepImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"Fetching ${file.file.getAbsolutePath}")

    val is = new BufferedInputStream(new FileInputStream(file.file))
    val formatOpt = Option(URLConnection.guessContentTypeFromStream(is)).flatMap { mimeType =>
      mimeTypeToImageFormat(mimeType)
    }.orElse {
      imageFilenameToFormat(file.file.getName)
    }
    is.close()

    formatOpt match {
      case Some(format) =>
        log.info(s"Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"Hashed: ${hash.hash}")
            Future.successful(Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(ImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(ImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  private def resizeImage(image: BufferedImage, boundingBox: Int): Try[BufferedImage] = Try {
    val img = Scalr.resize(image, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, boundingBox)
    log.info(s"Bounding box $boundingBox resized to ${img.getHeight} x ${img.getWidth}")
    img
  }

  // Returns Set of bounding box sizes
  private def calcSizesForImage(image: BufferedImage): Set[Int] = {
    val sizes = KeepImageSize.allSizes.map { size =>
      calcResizeBoundingBox(image, size.idealSize)
    }.filter(_.isDefined).map(_.get)

    sizes.foldRight(Set[Int]()) {
      case (b, a) =>
        a.find(existingSize => Math.abs(b - existingSize) < 100) match {
          case Some(alreadyHasACloseEnoughSize) => a
          case None => a + b
        }
    }.filterNot(i => i == Math.max(image.getWidth, image.getHeight)) // throw out original size
  }

  // Returns None if image can not be reasonably boxed to near the desired dimensions
  private def calcResizeBoundingBox(image: BufferedImage, size: ImageSize): Option[Int] = {
    val imgHeight = image.getHeight
    val imgWidth = image.getWidth

    val fudgeFactor = 0.50

    if (size.height * fudgeFactor > imgHeight && size.width * fudgeFactor > imgWidth) {
      // The size we want is just too far from the original.
      None
    } else {
      val h2 = Math.min(size.height, imgHeight)
      val w2 = Math.min(size.width, imgWidth)
      Some(Math.max(h2, w2))
    }
  }

  // Returns None if image could not be read.
  private def validateAndLoadImageFile(file: File): Try[BufferedImage] = {
    // ImageIO both can return null and throw exceptions. Isolate them.
    Try(Option(ImageIO.read(file))).map(t => Try(t.get)).flatten.map { image =>
      log.info(s"Validated and loaded. ${image.getHeight} x ${image.getWidth}")
      image
    }
  }

  private val imageFormatToJavaFormatName: ImageFormat => String = {
    case ImageFormat.JPG | ImageFormat("jpeg") => "jpeg"
    case _ => "png"
  }

  private val imageFormatToMimeType: ImageFormat => String = {
    case ImageFormat.JPG | ImageFormat("jpeg") => "image/jpeg"
    case ImageFormat.PNG => "image/png"
    case ImageFormat("gif") => "image/gif"
  }

  private val mimeTypeToImageFormat: String => Option[ImageFormat] = {
    case "image/jpeg" | "image/jpg" => Some(ImageFormat.JPG)
    case "image/png" => Some(ImageFormat.PNG)
    case "image/tiff" => Some(ImageFormat("tiff"))
    case "image/bmp" => Some(ImageFormat("bmp"))
    case "image/gif" => Some(ImageFormat("gif"))
    case _ => None
  }

  private val imageFilenameToFormat: String => Option[ImageFormat] = {
    case "jpeg" | "jpg" => Some(ImageFormat.JPG)
    case "tiff" => Some(ImageFormat("tiff"))
    case "bmp" => Some(ImageFormat("bmp"))
    case "gif" => Some(ImageFormat("gif"))
    case _ => None
  }

  private def bufferedImageToInputStream(img: BufferedImage, format: ImageFormat): Try[(ByteArrayInputStream, Int)] = {
    val os = new ByteArrayOutputStream()
    Try(ImageIO.write(img, imageFormatToJavaFormatName(format), os)) match {
      case Success(true) =>
        val is = new ByteArrayInputStream(os.toByteArray)
        os.close()
        Success((is, os.size))
      case Success(false) => Failure(new RuntimeException(s"No valid writer for $format"))
      case Failure(ex) => Failure(ex)
    }
  }

  private def hashImageFile(file: File): Try[ImageHash] = Try {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = new Array[Byte](1024 * 1024)
    val is = new FileInputStream(file)

    var len = is.read(buffer)
    while (len > 0) {
      digest.update(buffer, 0, len)
      len = is.read(buffer)
    }
    val md5sum = digest.digest()

    is.close()

    val bigInt = new BigInteger(1, md5sum)
    var bigIntStr = bigInt.toString(16)
    while (bigIntStr.length < 32) {
      bigIntStr = "0" + bigIntStr
    }
    ImageHash(bigIntStr)
  }

  private def fetchRemoteImage(imageUrl: String, timeoutMs: Int = 20000): Future[(ImageFormat, TemporaryFile)] = {

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

sealed trait ImageProcessState
sealed trait ImageProcessDone extends ImageProcessState
sealed trait KeepImageStoreInProgress extends ImageProcessState
sealed abstract class KeepImageStoreFailure(reason: String) extends ImageProcessState with ImageProcessDone
sealed abstract class KeepImageStoreFailureWithException(ex: Throwable, reason: String) extends KeepImageStoreFailure(reason)
object ImageProcessState {
  // In-process
  case class ImageLoadedAndHashed(file: TemporaryFile, format: ImageFormat, hash: ImageHash, sourceImageUrl: Option[String]) extends KeepImageStoreInProgress
  case class ImageValid(image: BufferedImage, format: ImageFormat, hash: ImageHash) extends KeepImageStoreInProgress
  case class ReadyToPersist(key: String, format: ImageFormat, is: ByteArrayInputStream, image: BufferedImage, bytes: Int) extends KeepImageStoreInProgress
  case class UploadedImage(key: String, format: ImageFormat, image: BufferedImage) extends KeepImageStoreInProgress

  // Failures
  case class UpstreamProviderFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "upstream_provider_failed")
  case object UpstreamProviderNoImage extends KeepImageStoreFailure("upstream_provider_no_image")
  case class SourceFetchFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "source_fetch_failed")
  case class HashFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "image_hash_failed")
  case class InvalidImage(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "invalid_image")
  case class DbPersistFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "db_persist_failed")
  case class CDNUploadFailed(ex: Throwable) extends KeepImageStoreFailureWithException(ex, "cdn_upload_failed")

  // Success
  case object StoreSuccess extends ImageProcessState with ImageProcessDone
  case class ExistingStoredImagesFound(images: Seq[KeepImage]) extends ImageProcessState with ImageProcessDone
}

sealed abstract class KeepImageSize(val name: String, val idealSize: ImageSize)
object KeepImageSize {
  case object Small extends KeepImageSize("icon", ImageSize(150, 150))
  case object Medium extends KeepImageSize("medium", ImageSize(400, 400))
  case object Large extends KeepImageSize("large", ImageSize(800, 800))
  case object XLarge extends KeepImageSize("xlarge", ImageSize(1500, 1500))

  val allSizes: Seq[KeepImageSize] = Seq(Small, Medium, Large, XLarge)

  def apply(size: ImageSize): KeepImageSize = {
    // Minimize the maximum dimension divergence from the KeepImageSizes
    allSizes.map(s => s -> maxDivergence(s.idealSize, size)).sortBy(_._2).head._1
  }

  def pickBest(idealSize: ImageSize, images: Seq[KeepImage]): Option[KeepImage] = {
    images.map(s => s -> maxDivergence(idealSize, s.imageSize)).sortBy(_._2).headOption.map(_._1)
  }

  def imageSizeFromString(sizeStr: String): Option[ImageSize] = {
    val lower = sizeStr.toLowerCase
    KeepImageSize.allSizes.find { size =>
      size.name == lower
    }.map(_.idealSize).orElse {
      lower.split("x").toList match {
        case width :: height :: Nil =>
          val imgTry = for {
            w <- Try(width.toInt)
            h <- Try(height.toInt)
          } yield ImageSize(w, h)
          imgTry.toOption
        case _ => None
      }
    }
  }

  @inline
  private def maxDivergence(s1: ImageSize, s2: ImageSize): Int = {
    Math.max(Math.abs(s1.height - s2.height), Math.abs(s1.width - s2.width))
  }
}

