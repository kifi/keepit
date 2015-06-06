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
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.model._
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

object ProcessImageRequest {
  implicit val ordering = new Ordering[ProcessImageRequest] {
    override def compare(x: ProcessImageRequest, y: ProcessImageRequest): Int = {
      (x, y) match {
        case (c1: CropImageRequest, c2: CropImageRequest) => c1.size.width - c2.size.width
        case (s1: ScaleImageRequest, s2: ScaleImageRequest) => s1.size.width - s2.size.width
        case (_: CropImageRequest, _: ScaleImageRequest) => 1
        case (_: ScaleImageRequest, _: CropImageRequest) => -1
      }
    }
  }
}

sealed abstract case class ProcessImageRequest(operation: ProcessImageOperation, size: ImageSize)
class ScaleImageRequest(size: ImageSize) extends ProcessImageRequest(operation = ProcessImageOperation.Scale, size)
class CropImageRequest(size: ImageSize) extends ProcessImageRequest(operation = ProcessImageOperation.Crop, size)

object ScaleImageRequest {
  def apply(boundingBox: Int): ScaleImageRequest = new ScaleImageRequest(ImageSize(boundingBox, boundingBox))
  def apply(imageSize: ImageSize): ScaleImageRequest = new ScaleImageRequest(imageSize)
  def apply(width: Int, height: Int): ScaleImageRequest = new ScaleImageRequest(ImageSize(width, height))
}

object CropImageRequest {
  def apply(imageSize: ImageSize): CropImageRequest = new CropImageRequest(imageSize)
  def apply(width: Int, height: Int): CropImageRequest = new CropImageRequest(ImageSize(width, height))
}

trait ProcessedImageHelper {

  val log: Logger
  val webService: WebService

  def fetchAndHashLocalImage(file: TemporaryFile): Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[pih] Fetching ${file.file.getAbsolutePath}")

    val formatOpt = detectImageType(file)

    formatOpt match {
      case Some(format) =>
        log.info(s"[pih] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[pih] Hashed: ${hash.hash}")
            Future.successful(Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, None)))
          case Failure(ex) =>
            Future.successful(Left(ImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(ImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  def fetchAndHashRemoteImage(imageUrl: String): Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[pih] Fetching $imageUrl")
    fetchRemoteImage(imageUrl).map {
      case (format, file) =>
        log.info(s"[pih] Fetched. format: $format, file: ${file.file.getAbsolutePath}")
        hashImageFile(file.file) match {
          case Success(hash) =>
            log.info(s"[pih] Hashed: ${hash.hash}")
            Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
          case Failure(ex) =>
            Left(ImageProcessState.HashFailed(ex))
        }
    }.recover {
      case ex: Throwable =>
        Left(ImageProcessState.SourceFetchFailed(ex))
    }
  }

  def buildPersistSet(sourceImage: ImageProcessState.ImageLoadedAndHashed, baseLabel: String, scaleCandidates: Seq[ScaledImageSize],
    cropCandidates: Seq[CroppedImageSize])(implicit photoshop: Photoshop): Either[ImageStoreFailure, Set[ImageProcessState.ReadyToPersist]] = {

    validateAndLoadImageFile(sourceImage.file.file) match {
      case Success(image) =>
        val outFormat = inputFormatToOutputFormat(sourceImage.format)
        val imageSize = ImageSize(image.getWidth, image.getHeight)
        val sizes = calcSizesForImage(imageSize, scaleCandidates, cropCandidates)
        val resizedImages = processAndPersistImages(image, baseLabel, sourceImage.hash, sourceImage.format, sizes)(photoshop)

        resizedImages match {
          case Right(resizedSet) =>
            val original = bufferedImageToInputStream(image, outFormat).map {
              case (is, bytes) =>
                val key = ImagePath(baseLabel, sourceImage.hash, imageSize, ProcessImageOperation.Original, sourceImage.format)
                ImageProcessState.ReadyToPersist(key, outFormat, is, image, bytes, ProcessImageOperation.Original)
            }
            original match {
              case Success(o) => Right(resizedSet + o)
              case Failure(ex) => Left(ImageProcessState.InvalidImage(ex))
            }
          case Left(err) => Left(err)
        }
      case Failure(ex) =>
        log.error(s"s[pih] validateAndLoadImageFile failure", ex)
        Left(ImageProcessState.InvalidImage(ex))
    }
  }

  protected def processAndPersistImages(image: BufferedImage, baseLabel: String, hash: ImageHash,
    outFormat: ImageFormat, sizes: Set[ProcessImageRequest])(implicit photoshop: Photoshop) = {
    val resizedImages = sizes.map { processImageSize =>
      log.info(s"[pih] processing images baseLabel=$baseLabel format=$outFormat imageWidth=${image.getWidth} imageHeight=${image.getHeight} to $processImageSize")

      def process(): Try[BufferedImage] = processImageSize match {
        case c if c.operation == ProcessImageOperation.Crop => photoshop.cropImage(image, outFormat, c.size.width, c.size.height)
        case s => photoshop.resizeImage(image, outFormat, s.size.width, s.size.height)
      }

      process().map { resizedImage =>
        bufferedImageToInputStream(resizedImage, outFormat).map {
          case (is, bytes) =>
            val key = ImagePath(baseLabel, hash, ImageSize(resizedImage), processImageSize.operation, outFormat)
            ImageProcessState.ReadyToPersist(key, outFormat, is, resizedImage, bytes, processImageSize.operation)
        }
      }.flatten match {
        case Success(img) => Right(img)
        case Failure(ex) => Left(ImageProcessState.InvalidImage(ex))
      }
    }

    resizedImages.find(_.isLeft) match {
      case Some(error) => // failure of at least one of the images
        log.error("s[pih] resizedImages failure $error")
        Left(error.left.get)
      case None =>
        Right(resizedImages.map(_.right.get))
    }
  }

  protected def filterProcessImageRequests(expectedImages: Set[ProcessImageRequest], existingImages: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
    // hack to eliminate expecting scale versions that have the same scaled bounding box
    @inline def boundingBox(imageSize: ImageSize): Int = Math.max(imageSize.width, imageSize.height)
    val existingScaleBoundingBoxes = existingImages.collect { case ProcessImageRequest(ProcessImageOperation.Scale, size) => boundingBox(size) }
    expectedImages filterNot {
      case ProcessImageRequest(ProcessImageOperation.Scale, size) => existingScaleBoundingBoxes.contains(boundingBox(size))
      case otherRequest => existingImages.contains(otherRequest)
    }
  }

  // Returns Set of bounding box sizes
  protected def calcSizesForImage(imageSize: ImageSize, scaleCandidates: Seq[ScaledImageSize], cropCandidates: Seq[CroppedImageSize]): Set[ProcessImageRequest] = {
    val scaleSizes = scaleCandidates.map { size =>
      calcResizeBoundingBox(imageSize, size.idealSize)
    }.flatten

    val scaleImageRequests = {
      var t = 0
      scaleSizes.sorted.flatMap { x =>
        if (x - t > 100) {
          t = x
          Some(x)
        } else None
      }.filterNot { i => i == Math.max(imageSize.width, imageSize.height) }.
        map { x => ScaleImageRequest(x) }
    }

    val imgHeight = imageSize.height
    val imgWidth = imageSize.width

    log.info(s"[csfi] imageSize=${imageSize.width}x${imageSize.height} cropCandidates=$cropCandidates")
    val cropImageRequests = cropCandidates.filterNot { cropSize =>
      val size = cropSize.idealSize
      def isAlmostSameAspectRatio = Math.abs(imgWidth.toFloat / imgHeight - cropSize.aspectRatio) < 0.01

      // 1) if either the width or height of the actual image is smaller than our crop, abort
      // 2) or, if the aspect ratio of the image and crop size are the same and there exists
      //    a scale bounding box close to enough to our crop candidate, we can skip the crop
      imgWidth < size.width || imgHeight < size.height ||
        (isAlmostSameAspectRatio && scaleImageRequests.exists { scaleSize =>
          val candidateCropWidth = cropSize.idealSize.width
          val candidateCropHeight = cropSize.idealSize.height
          val scaleWidth = scaleSize.size.width
          val scaleHeight = scaleSize.size.height

          scaleWidth >= candidateCropWidth && scaleHeight >= candidateCropHeight &&
            scaleWidth - candidateCropWidth < 100 && scaleHeight - candidateCropHeight < 100
        })
    }.map { c => CropImageRequest(c.idealSize) }

    log.info(s"[csfi] imageSize=${imageSize.width}x${imageSize.height} cropRequests=$cropImageRequests")
    (scaleImageRequests ++ cropImageRequests).toSet
  }

  // Returns None if image can not be reasonably boxed to near the desired dimensions
  protected def calcResizeBoundingBox(imageSize: ImageSize, size: ImageSize): Option[Int] = {
    val imgHeight = imageSize.height
    val imgWidth = imageSize.width

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

sealed abstract class ProcessedImageSize(val name: String, val idealSize: ImageSize, val operation: ProcessImageOperation) {
  val aspectRatio = idealSize.width.toFloat / idealSize.height
}

sealed abstract class ScaledImageSize(name: String, idealSize: ImageSize) extends ProcessedImageSize(name, idealSize, ProcessImageOperation.Scale)
sealed abstract class CroppedImageSize(name: String, idealSize: ImageSize) extends ProcessedImageSize(name + "-crop", idealSize, ProcessImageOperation.Crop)

object ScaledImageSize {
  case object Small extends ScaledImageSize("small", ImageSize(150, 150))
  case object Medium extends ScaledImageSize("medium", ImageSize(400, 400))
  case object Large extends ScaledImageSize("large", ImageSize(1000, 1000))
  case object XLarge extends ScaledImageSize("xlarge", ImageSize(1500, 1500))

  val allSizes: Seq[ScaledImageSize] = Seq(Small, Medium, Large, XLarge)
}

object CroppedImageSize {
  case object Small extends CroppedImageSize("small", ImageSize(150, 150))

  val allSizes: Seq[CroppedImageSize] = Seq(Small)
}

object ProcessedImageSize {
  val Small = ScaledImageSize.Small
  val Medium = ScaledImageSize.Medium
  val Large = ScaledImageSize.Large
  val XLarge = ScaledImageSize.XLarge

  val allSizes: Seq[ProcessedImageSize] = Seq(Small, Medium, Large, XLarge)

  def apply(size: ImageSize): ProcessedImageSize = {
    // Minimize the maximum dimension divergence from the ProcessedImageSizes
    allSizes.map(s => s -> maxDivergence(s.idealSize, size)).
      sortBy(_._2).head._1
  }

  def pickBestImage[T <: BaseImage](idealSize: ImageSize, images: Seq[T], strictAspectRatio: Boolean): Option[T] = {
    pickByIdealImageSize(idealSize, images, strictAspectRatio)(_.imageSize)
  }

  def pickByIdealImageSize[I](idealSize: ImageSize, images: Iterable[I], strictAspectRatio: Boolean)(getSize: I => ImageSize): Option[I] = {
    images.collect {
      case image if !strictAspectRatio || isAlmostTheSameAspectRatio(idealSize, getSize(image)) =>
        image -> maxDivergence(idealSize, getSize(image))
    }.toSeq.sortBy(_._2).headOption.map(_._1)
  }

  def imageSizeFromString(sizeStr: String): Option[ImageSize] = {
    val lower = sizeStr.toLowerCase
    allSizes.find { size =>
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
  private def isAlmostTheSameAspectRatio(size1: ImageSize, size2: ImageSize): Boolean = {
    val ratio1 = size1.width.toFloat / size1.height
    val ratio2 = size2.width.toFloat / size2.height
    Math.abs(ratio1 - ratio2) < 0.01
  }

  @inline
  private def maxDivergence(s1: ImageSize, s2: ImageSize): Int = {
    Math.max(Math.abs(s1.height - s2.height), Math.abs(s1.width - s2.width))
  }
}
