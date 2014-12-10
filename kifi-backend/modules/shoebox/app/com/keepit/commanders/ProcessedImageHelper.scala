package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io._
import java.math.BigInteger
import java.net.URLConnection
import java.security.MessageDigest
import javax.imageio.ImageIO

import com.keepit.common.core.File
import com.keepit.common.images.Photoshop
import com.keepit.common.net.{ URI, WebService }
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.store.ImageSize
import com.keepit.model._
import org.imgscalr.Scalr
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

trait ProcessedImageHelper {
  val log: Logger
  val webService: WebService

  val originalLabel: String = "_o"

  def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[BaseImageStoreFailure, BaseImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching ${file.file.getAbsolutePath}")

    val formatOpt = detectImageType(file)

    formatOpt match {
      case Some(format) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Future.successful(Right(BaseImageProcessState.ImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(BaseImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(BaseImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  def fetchAndHashRemoteImage(imageUrl: String): Future[Either[BaseImageStoreFailure, BaseImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[kic] Fetching $imageUrl")
    fetchRemoteImage(imageUrl).map {
      case (format, file) =>
        log.info(s"[kic] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[kic] Hashed: ${hash.hash}")
            Right(BaseImageProcessState.ImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
          case Failure(ex) =>
            Left(BaseImageProcessState.HashFailed(ex))
        }
    }.recover {
      case ex: Throwable =>
        Left(BaseImageProcessState.SourceFetchFailed(ex))
    }
  }

  def buildPersistSet(sourceImage: BaseImageProcessState.ImageLoadedAndHashed, baseLabel: String)(implicit photoshop: Photoshop): Either[BaseImageStoreFailure, Set[BaseImageProcessState.ReadyToPersist]] = {
    val outFormat = inputFormatToOutputFormat(sourceImage.format)
    def keygen(width: Int, height: Int, label: String = "") = {
      baseLabel + "/" + sourceImage.hash.hash + "_" + width + "x" + height + label + "." + outFormat.value
    }
    validateAndLoadImageFile(sourceImage.file.file) match {
      case Success(image) =>
        val resizedImages = calcSizesForImage(image).map { boundingBox =>
          log.info(s"[kic] Using bounding box $boundingBox px")
          photoshop.resizeImage(image, sourceImage.format, boundingBox).map { resizedImage =>
            bufferedImageToInputStream(resizedImage, outFormat).map {
              case (is, bytes) =>
                val key = keygen(resizedImage.getWidth, resizedImage.getHeight)
                BaseImageProcessState.ReadyToPersist(key, outFormat, is, resizedImage, bytes)
            }
          }.flatten match {
            case Success(img) => Right(img)
            case Failure(ex) => Left(BaseImageProcessState.InvalidImage(ex))
          }
        }

        resizedImages.find(_.isLeft) match {
          case Some(error) => // failure of at least one of the images
            Left(error.left.get)
          case None =>
            val original = bufferedImageToInputStream(image, inputFormatToOutputFormat(sourceImage.format)).map {
              case (is, bytes) =>
                val key = keygen(image.getWidth, image.getHeight, originalLabel)
                BaseImageProcessState.ReadyToPersist(key, outFormat, is, image, bytes)
            }
            original match {
              case Success(o) =>
                val resizedSet = resizedImages.collect { case Right(img) => img }
                Right(resizedSet + o)
              case Failure(ex) => Left(BaseImageProcessState.InvalidImage(ex))
            }
        }
      case Failure(ex) =>
        Left(BaseImageProcessState.InvalidImage(ex))
    }
  }

  // Returns Set of bounding box sizes
  protected def calcSizesForImage(image: BufferedImage): Set[Int] = {
    val sizes = ProcessedImageSize.allSizes.map { size =>
      calcResizeBoundingBox(image, size.idealSize)
    }.flatten

    var t = 0
    sizes.sorted.flatMap { x =>
      if (x - t > 100) {
        t = x
        Some(x)
      } else None
    }.filterNot(i => i == Math.max(image.getWidth, image.getHeight)).toSet
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
    Try(Option(ImageIO.read(file))).map(t => Try(t.getOrElse(throw new Exception("ImageIO returned null image")))).flatten.map { image =>
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
    case t if t.startsWith("image/jpeg") || t.startsWith("image/jpg") => Some(ImageFormat.JPG)
    case t if t.startsWith("image/png") => Some(ImageFormat.PNG)
    case t if t.startsWith("image/tiff") => Some(ImageFormat("tiff"))
    case t if t.startsWith("image/bmp") => Some(ImageFormat("bmp"))
    case t if t.startsWith("image/gif") => Some(ImageFormat("gif"))
    case _ => None
  }

  protected val imageFilenameToFormat: String => Option[ImageFormat] = {
    case "jpeg" | "jpg" => Some(ImageFormat.JPG)
    case "png" => Some(ImageFormat.PNG)
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

  protected def detectImageType(file: TemporaryFile): Option[ImageFormat] = {
    val is = new BufferedInputStream(new FileInputStream(file.file))
    val formatOpt = Option(URLConnection.guessContentTypeFromStream(is)).flatMap { mimeType =>
      mimeTypeToImageFormat(mimeType)
    }.orElse {
      imageFilenameToFormat(file.file.getName)
    }
    is.close()
    formatOpt
  }

  private val remoteFetchConsolidater = new RequestConsolidator[String, (ImageFormat, TemporaryFile)](2.minutes)

  protected def fetchRemoteImage(imageUrl: String, timeoutMs: Int = 20000): Future[(ImageFormat, TemporaryFile)] = {
    remoteFetchConsolidater(imageUrl) { imageUrl =>
      webService.url(imageUrl).withRequestTimeout(20000).withFollowRedirects(true).getStream().flatMap {
        case (headers, streamBody) =>
          val formatOpt = headers.headers.get("Content-Type").flatMap(_.headOption)
            .flatMap(mimeTypeToImageFormat).orElse {
              val path = URI.parse(imageUrl).toOption.flatMap(_.path).getOrElse(imageUrl)
              imageFilenameToFormat(path.substring(path.lastIndexOf('.') + 1))
            }

          if (headers.status != 200) {
            Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}, $imageUrl"))
          } else {
            val tempFile = TemporaryFile(prefix = "remote-file")
            tempFile.file.deleteOnExit()
            val outputStream = new FileOutputStream(tempFile.file)

            val maxSize = 1024 * 1024 * 16

            var len = 0
            val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
              len += bytes.length
              if (len > maxSize) { // max original size
                outputStream.close()
                throw new Exception(s"Original image too large (> $len bytes): $imageUrl")
              } else {
                outputStream.write(bytes)
              }
            }

            streamBody.run(iteratee) andThen {
              case _ =>
                outputStream.close()
            } flatMap { _ =>
              formatOpt.orElse(detectImageType(tempFile)) map { format =>
                Future.successful((format, tempFile))
              } getOrElse {
                Future.failed(new Exception(s"Unknown image type, ${headers.headers.get("Content-Type")}, $imageUrl"))
              }
            }
          }
      }
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

sealed abstract class ProcessedImageSize(val name: String, val idealSize: ImageSize)
object ProcessedImageSize {
  case object Small extends ProcessedImageSize("small", ImageSize(150, 150))
  case object Medium extends ProcessedImageSize("medium", ImageSize(400, 400))
  case object Large extends ProcessedImageSize("large", ImageSize(1000, 1000))
  case object XLarge extends ProcessedImageSize("xlarge", ImageSize(1500, 1500))

  val allSizes: Seq[ProcessedImageSize] = Seq(Small, Medium, Large, XLarge)

  def apply(size: ImageSize): ProcessedImageSize = {
    // Minimize the maximum dimension divergence from the ProcessedImageSizes
    allSizes.map(s => s -> maxDivergence(s.idealSize, size)).sortBy(_._2).head._1
  }

  def pickBestImage(idealSize: ImageSize, images: Seq[BaseImage]): Option[BaseImage] = {
    images.map(s => s -> maxDivergence(idealSize, s.imageSize)).sortBy(_._2).headOption.map(_._1)
  }

  def imageSizeFromString(sizeStr: String): Option[ImageSize] = {
    val lower = sizeStr.toLowerCase
    ProcessedImageSize.allSizes.find { size =>
      size.name == lower
    }.map(_.idealSize).orElse {
      lower.split("x").toList match {
        case width :: height :: Nil =>
          Try(ImageSize(width.toInt, height.toInt)).toOption
        case _ => None
      }
    }
  }

  @inline
  private def maxDivergence(s1: ImageSize, s2: ImageSize): Int = {
    Math.max(Math.abs(s1.height - s2.height), Math.abs(s1.width - s2.width))
  }
}
