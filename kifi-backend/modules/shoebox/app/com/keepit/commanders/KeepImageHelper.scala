package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.{ FileInputStream, ByteArrayOutputStream, ByteArrayInputStream }
import java.math.BigInteger
import java.security.MessageDigest
import javax.imageio.ImageIO

import com.keepit.common.core._
import com.keepit.common.logging.Logging
import com.keepit.common.store.ImageSize
import com.keepit.model.{ KeepImage, ImageHash, ImageFormat }
import play.api.Logger
import play.api.libs.Files.TemporaryFile

import scala.util.{ Failure, Success, Try }

trait KeepImageHelper {
  val log: Logger
  // Returns Set of bounding box sizes
  protected def calcSizesForImage(image: BufferedImage): Set[Int] = {
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
  protected def calcResizeBoundingBox(image: BufferedImage, size: ImageSize): Option[Int] = {
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
  protected def validateAndLoadImageFile(file: File): Try[BufferedImage] = {
    // ImageIO both can return null and throw exceptions. Isolate them.
    Try(Option(ImageIO.read(file))).map(t => Try(t.get)).flatten.map { image =>
      log.info(s"Validated and loaded. ${image.getHeight} x ${image.getWidth}")
      image
    }
  }

  protected val inputFormatToOutputFormat: ImageFormat => ImageFormat = {
    case ImageFormat.JPG => ImageFormat.JPG
    case _ => ImageFormat.PNG
  }

  protected val imageFormatToJavaFormatName: ImageFormat => String = {
    case ImageFormat.JPG | ImageFormat("jpeg") => "jpeg"
    case _ => "png"
  }

  protected val imageFormatToMimeType: ImageFormat => String = {
    case ImageFormat.JPG | ImageFormat("jpeg") => "image/jpeg"
    case ImageFormat.PNG => "image/png"
    case ImageFormat("gif") => "image/gif"
  }

  protected val mimeTypeToImageFormat: String => Option[ImageFormat] = {
    case "image/jpeg" | "image/jpg" => Some(ImageFormat.JPG)
    case "image/png" => Some(ImageFormat.PNG)
    case "image/tiff" => Some(ImageFormat("tiff"))
    case "image/bmp" => Some(ImageFormat("bmp"))
    case "image/gif" => Some(ImageFormat("gif"))
    case _ => None
  }

  protected val imageFilenameToFormat: String => Option[ImageFormat] = {
    case "jpeg" | "jpg" => Some(ImageFormat.JPG)
    case "tiff" => Some(ImageFormat("tiff"))
    case "bmp" => Some(ImageFormat("bmp"))
    case "gif" => Some(ImageFormat("gif"))
    case _ => None
  }

  protected def bufferedImageToInputStream(img: BufferedImage, format: ImageFormat): Try[(ByteArrayInputStream, Int)] = {
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

  protected def hashImageFile(file: File): Try[ImageHash] = Try {
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
  case object Large extends KeepImageSize("large", ImageSize(1000, 1000))
  case object XLarge extends KeepImageSize("xlarge", ImageSize(2000, 2000))

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
