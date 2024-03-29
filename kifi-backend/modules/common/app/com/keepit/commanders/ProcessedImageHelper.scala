package com.keepit.commanders

import java.io._
import java.math.BigInteger
import java.net.URLConnection
import java.security.MessageDigest
import com.google.inject.{Inject, ImplementedBy, Singleton}

import com.keepit.common.core.File
import com.keepit.common.images.{ Photoshop, RawImageInfo }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URI, WebService }
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.store.{ ImageOffset, ImagePath, ImageSize }
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

sealed abstract class ProcessImageRequest {
  def operation: ProcessImageOperation
  def size: ImageSize
  def pathFragment: String
}
case class ScaleImageRequest(size: ImageSize) extends ProcessImageRequest {
  val operation = ProcessImageOperation.Scale
  val pathFragment = size.width + "x" + size.height + operation.fileNameSuffix
}
case class CenteredCropImageRequest(size: ImageSize) extends ProcessImageRequest {
  val operation = ProcessImageOperation.CenteredCrop
  val pathFragment = size.width + "x" + size.height + operation.fileNameSuffix
}
case class CropScaleImageRequest(offset: ImageOffset, cropSize: ImageSize, finalSize: ImageSize) extends ProcessImageRequest {
  val operation = ProcessImageOperation.CropScale
  def size: ImageSize = finalSize
  val pathFragment = s"${cropSize.width}x${cropSize.height}-${offset.x}x${offset.y}-${finalSize.width}x${finalSize.height}" + operation.fileNameSuffix
}

object ScaleImageRequest {
  def apply(boundingBox: Int): ScaleImageRequest = ScaleImageRequest(ImageSize(boundingBox, boundingBox))
  def apply(width: Int, height: Int): ScaleImageRequest = ScaleImageRequest(ImageSize(width, height))
}

object CenteredCropImageRequest {
  def apply(width: Int, height: Int): CenteredCropImageRequest = new CenteredCropImageRequest(ImageSize(width, height))
}

trait ProcessedImageHelper {

  val log: Logger
  val webService: WebService
  val cleanup: ImageCleanup

  def fetchAndHashLocalImage(file: File): Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    log.info(s"[pih] Fetching ${file.getAbsolutePath}")

    val formatOpt = detectImageType(file)

    formatOpt match {
      case Some(format) =>
        hashImageFile(file) match {
          case Success(hash) if !ProcessedImageHelper.blacklistedHash.contains(hash.hash) =>
            Future.successful(Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, None)))
          case Success(hash) => // blacklisted hash
            Future.successful(Left(ImageProcessState.BlacklistedImage))
          case Failure(ex) =>
            Future.successful(Left(ImageProcessState.HashFailed(ex)))
        }
      case None =>
        Future.successful(Left(ImageProcessState.SourceFetchFailed(new RuntimeException(s"Unknown image type"))))
    }
  }

  def fetchAndHashRemoteImage(imageUrl: String): Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = {
    if (ProcessedImageHelper.blacklistedUrl.contains(imageUrl)) {
      Future.successful(Left(ImageProcessState.BlacklistedImage))
    } else {
      log.info(s"[pih] Fetching $imageUrl")
      val loadedF: Future[Either[ImageStoreFailure, ImageProcessState.ImageLoadedAndHashed]] = fetchRemoteImage(imageUrl).map {
        case (format, file) =>
          hashImageFile(file) match {
            case Success(hash) if !ProcessedImageHelper.blacklistedHash.contains(hash.hash) =>
              Right(ImageProcessState.ImageLoadedAndHashed(file, format, hash, Some(imageUrl)))
            case Success(hash) => // blacklisted hash
              Left(ImageProcessState.BlacklistedImage)
            case Failure(ex) =>
              Left(ImageProcessState.HashFailed(ex))
          }
      }
      loadedF.recover {
        case ex: Throwable =>
          Left(ImageProcessState.SourceFetchFailed(ex))
      }
    }
  }

  def buildPersistSet(sourceImage: ImageProcessState.ImageLoadedAndHashed, baseLabel: String, scaleCandidates: Seq[ScaledImageSize],
    cropCandidates: Seq[CroppedImageSize])(implicit photoshop: Photoshop): Either[ImageStoreFailure, Set[ImageProcessState.ReadyToPersist]] = {

    validateAndGetImageInfo(sourceImage.file) match {
      case Success(imageInfo) =>
        val image = sourceImage.file
        val outFormat = inputFormatToOutputFormat(sourceImage.format)
        val imageSize = ImageSize(imageInfo.width, imageInfo.height)
        val sizes = calcSizesForImage(imageSize, scaleCandidates, cropCandidates)
        val resizedImages = processAndPersistImages(image, baseLabel, sourceImage.hash, sourceImage.format, sizes)(photoshop)

        resizedImages match {
          case Right(resizedSet) =>
            val original = {
              val key = ImagePath(baseLabel, sourceImage.hash, imageSize, ProcessImageOperation.Original, sourceImage.format)
              ImageProcessState.ReadyToPersist(key, outFormat, image, imageInfo, ProcessImageOperation.Original)
            }
            Right(resizedSet ++ Set(original))
          case Left(err) => Left(err)
        }
      case Failure(ex) =>
        log.error(s"[pih] validateAndGetImageInfo failure", ex)
        Left(ImageProcessState.InvalidImage(ex))
    }
  }

  protected def processAndPersistImages(image: File, baseLabel: String, hash: ImageHash,
    outFormat: ImageFormat, sizes: Set[ProcessImageRequest])(implicit photoshop: Photoshop) = {
    val resizedImages = sizes.map { processImageRequest =>
      log.info(s"[pih] processing images baseLabel=$baseLabel format=$outFormat to $processImageRequest")

      val isCropScale = processImageRequest match {
        case _: CropScaleImageRequest => true
        case _ => false
      }
      def process(): Try[File] = processImageRequest match {
        case CropScaleImageRequest(offset, cropSize, scaleSize) => photoshop.cropScaleImage(image, outFormat, offset.x, offset.y, cropSize.width, cropSize.height, scaleSize.width, scaleSize.height)
        case CenteredCropImageRequest(size) => photoshop.centeredCropImage(image, outFormat, size.width, size.height)
        case ScaleImageRequest(size) => photoshop.resizeImage(image, outFormat, size.width, size.height)
      }

      process().map { resizedImage =>
        validateAndGetImageInfo(resizedImage).map { imageInfo =>
          val key = if (isCropScale) {
            ImagePath(baseLabel, hash, processImageRequest, outFormat)
          } else {
            ImagePath(baseLabel, hash, ImageSize(imageInfo.width, imageInfo.height), processImageRequest.operation, outFormat)
          }
          ImageProcessState.ReadyToPersist(key, outFormat, resizedImage, imageInfo, processImageRequest.operation)
        }
      }.flatten match {
        case Success(img) => Right(img)
        case Failure(ex) => Left(ImageProcessState.InvalidImage(ex))
      }
    }

    resizedImages.find(_.isLeft) match {
      case Some(error) => // failure of at least one of the images
        log.error(s"[pih] resizedImages failure ${error.toString.take(220)}")
        Left(error.left.get)
      case None =>
        Right(resizedImages.map(_.right.get))
    }
  }

  // All the ProcessImageRequests in A that are also in B
  protected def intersectProcessImageRequests(A: Set[ProcessImageRequest], B: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
    @inline def boundingBox(imageSize: ImageSize): Int = Math.max(imageSize.width, imageSize.height)
    val Bp = B.collect { case ScaleImageRequest(size) => boundingBox(size) }
    A filter {
      case ScaleImageRequest(size) => Bp.contains(boundingBox(size))
      case otherRequest => B.contains(otherRequest)
    }
  }
  // All the ProcessImageRequests in A that are NOT in B
  protected def diffProcessImageRequests(A: Set[ProcessImageRequest], B: Set[ProcessImageRequest]): Set[ProcessImageRequest] = {
    // hack to eliminate expecting scale versions that have the same scaled bounding box
    @inline def boundingBox(imageSize: ImageSize): Int = Math.max(imageSize.width, imageSize.height)
    val Bp = B.collect { case ScaleImageRequest(size) => boundingBox(size) }
    A filterNot {
      case ScaleImageRequest(size) => Bp.contains(boundingBox(size))
      case otherRequest => B.contains(otherRequest)
    }
  }

  // Returns Set of bounding box sizes
  protected def calcSizesForImage(imageSize: ImageSize, scaleCandidates: Seq[ScaledImageSize], cropCandidates: Seq[CroppedImageSize]): Set[ProcessImageRequest] = {
    val scaleSizes = scaleCandidates.flatMap { size =>
      calcResizeBoundingBox(imageSize, size.idealSize)
    }

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
    }.map { c => CenteredCropImageRequest(c.idealSize) }

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
  protected def validateAndGetImageInfo(file: File)(implicit photoshop: Photoshop): Try[RawImageInfo] = {
    photoshop.imageInfo(file)
  }

  protected val inputFormatToOutputFormat: ImageFormat => ImageFormat = {
    case ImageFormat.JPG => ImageFormat.JPG
    case ImageFormat.GIF => ImageFormat.GIF
    case _ => ImageFormat.PNG
  }


  protected val imageFormatToMimeType: ImageFormat => String = {
    case ImageFormat.JPG | ImageFormat("jpeg") => "image/jpeg"
    case ImageFormat.PNG => "image/png"
    case ImageFormat("gif") => "image/gif"
  }

  protected def detectImageType(file: File): Option[ImageFormat] = {
    Try {
      val is = new BufferedInputStream(new FileInputStream(file))

      is.mark(16)
      val c1 = is.read()
      val c2 = is.read()
      val c3 = is.read()
      val c4 = is.read()
      val c5 = is.read()
      val c6 = is.read()
      val c7 = is.read()
      val c8 = is.read()
      val c9 = is.read()
      val c10 = is.read()
      val c11 = is.read()
      val c12 = is.read()
      val c13 = is.read()
      val c14 = is.read()
      val c15 = is.read()
      val c16 = is.read()
      is.reset()
      is.close()

      val formatOpt = if (c1 == 'G' && c2 == 'I' && c3 == 'F' && c4 == '8') {
        Some(ImageFormat.GIF)
      } else if (c1 == '#' && c2 == 'd' && c3 == 'e' && c4 == 'f') {
        Some(ImageFormat("bmp"))
      } else if (c1 == 137 && c2 == 80 && c3 == 78 && c4 == 71 && c5 == 13 && c6 == 10 && c7 == 26 && c8 == 10) {
        Some(ImageFormat.PNG)
      } else if (c1 == 0xFF && c2 == 0xD8 && c3 == 0xFF) {
        Some(ImageFormat.JPG)
      } else if ((c1 == 0x49 && c2 == 0x49 && c3 == 0x2A && c4 == 0x00) || (c1 == 0x4D && c2 == 0x4D && c3 == 0x00 && c4 == 0x2A)) {
        Some(ImageFormat("tiff"))
      } else if ((c1 == '<' && c2 == '?' && c3 == 'x' && c4 == 'm' && c5 == 'l' && c6 == ' ') ||
        (c1 == 0xef && c2 == 0xbb && c3 == 0xbf && c4 == '<' && c5 == '?' && c6 == 'x') ||
        (c1 == 0xfe && c2 == 0xff && c3 == 0 && c4 == '<' && c5 == 0 && c6 == '?' && c7 == 0 && c8 == 'x') ||
        (c1 == 0xff && c2 == 0xfe && c3 == '<' && c4 == 0 && c5 == '?' && c6 == 0 && c7 == 'x' && c8 == 0) ||
        (c1 == 0x00 && c2 == 0x00 && c3 == 0xfe && c4 == 0xff && c5 == 0 && c6 == 0 && c7 == 0 && c8 == '<' && c9 == 0 && c10 == 0 && c11 == 0 && c12 == '?' && c13 == 0 && c14 == 0 && c15 == 0 && c16 == 'x') ||
        (c1 == 0xff && c2 == 0xfe && c3 == 0x00 && c4 == 0x00 && c5 == '<' && c6 == 0 && c7 == 0 && c8 == 0 && c9 == '?' && c10 == 0 && c11 == 0 && c12 == 0 && c13 == 'x' && c14 == 0 && c15 == 0 && c16 == 0)
      ) {
        Some(ImageFormat("svg"))
      } else if (c1 == 'W' && c2 == 'E' && c3 == 'B' && c4 == 'P') {
        Some(ImageFormat("webp"))
      } else {
        None
      }

      formatOpt
    }.toOption.flatten
  }

  private val remoteFetchConsolidater = new RequestConsolidator[String, (ImageFormat, File)](2.minutes)

  protected def fetchRemoteImage(imageUrl: String, timeoutMs: Int = 20000): Future[(ImageFormat, File)] = {
    remoteFetchConsolidater(imageUrl) { imageUrl =>
      webService.url(imageUrl).withRequestTimeout(20000).withFollowRedirects(true).getStream().flatMap {
        case (headers, streamBody) =>

          if (headers.status != 200) {
            Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}, $imageUrl"))
          } else {
            val urlname = imageUrl.drop(5).replaceAll("""\W""","").take(20)
            val tempFile = File.createTempFile("rf-" + urlname, "")
            tempFile.deleteOnExit()
            cleanup.cleanup(tempFile)
            val outputStream = new FileOutputStream(tempFile)

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
              detectImageType(tempFile) map { format =>
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

object ProcessedImageHelper {
  val blacklistedUrl = Set(
    "http://s0.wp.com/i/blank.jpg",
    "https://s0.wp.com/i/blank.jpg",
    "http://wordpress.com/i/blank.jpg",
    "http://assets.tumblr.com/images/og/link_200.png",
    "https://ssl.gstatic.com/accounts/ui/avatar_2x.png",
    "http://cdn.sstatic.net/askubuntu/img/apple-touch-icon@2.png",
    "http://assets.tumblr.com/images/og/text_200.png",
    "http://djty7jcqog9qu.cloudfront.net/i/7dce329f880b899a462be1c476c79291_192x192_o.png",
    "http://djty7jcqog9qu.cloudfront.net/images/4bc601cc-05e9-48bf-aaff-9da2915626d6/0/hSPUi.jpg",
    "http://blickwinkel-portal.de/gm-bilder/images/front_erch1.jpg"
  )
  val blacklistedHash = Set(
    "bf322fb70d88a207194c5d25f189d692", // wordpress blank image
    "25b2026ce9381eb01b786361d06c6330", // weird useless icon that has tons of URLs
    "1d38edd471862bc4b31a6e882a8cd478", // noise.png, a blank image, tons of URLs
    "1300018473cc0038187aaa0e2604fa27", // wordpress ghost user image
    "9a35b7c212298d54eed6841ada897fd1", // google spritesheet
    "8c65758da1765bb830f80ce4403bad13", // common wordpress theme background
    "55d25e9dc950d5db4d53a3b195c046c6", // tracking pixel
    "acd642661699ba6a4dc1679e1bab76c1", // common wordpress theme background
    "8bdcfa66ef2b8ac0a9fb33401eef40e4", // infoq logo
    "d57486ab23b722832d87a0e238acfd62", // common wordpress theme background
    "a0d449084ea7eb23a14f0c5c2f8a7dea", // common blogger theme background
    "b40e39a8e3747e74f4dfcf6d88ecc535", // common wordpress theme background
    "3f7702975298d2f0b583c83b1ce189f5", // common web forum logo
    "c4d171422792ac800e504a7a8b2eb624", // google spritesheet
    "c8f1281dd971abb027322c76e3f55835", // common wordpress theme background
    "92860867f25ce200ea94779d2d55d737", // common wordpress theme background
    "a922e59e0e523071923eb2c51517b832", // black image,
    "000775e04f04f071b6d1000035238e6c", // tumblr link image
    "04d3fc7ff4e9f9fccc166beabd397558", // stackoverflow logo
    "7dce329f880b899a462be1c476c79291", // blogger ghost image
    "c8fcb8f216e75f1c02b93fbc689a81c1", // askubuntu logo
    "989d155fe0261a9d9938549a3c2f8168", // ebay spritesheet
    "30d57a48f6f155affd542a528dd02506", // Aa icon
    "cc1023b3cf6c90f2b838495b3e9917d4", // Youtube error image
    "cb6b256050c23a71b2b4996e88f77225", // missing link logo
    "42573fffcf02644c0b5a14074afc3c64", // ghost icon
    "7595be5afed36a595ac3161b585a2911" // white image
  )
}

sealed abstract class ProcessedImageSize(val name: String, val idealSize: ImageSize, val operation: ProcessImageOperation) {
  val aspectRatio = idealSize.width.toFloat / idealSize.height
}

sealed abstract class ScaledImageSize(name: String, idealSize: ImageSize) extends ProcessedImageSize(name, idealSize, ProcessImageOperation.Scale)
sealed abstract class CroppedImageSize(name: String, idealSize: ImageSize) extends ProcessedImageSize(name + "-crop", idealSize, ProcessImageOperation.CenteredCrop)
sealed abstract class CropScaledImageSize(name: String, idealSize: ImageSize) extends ProcessedImageSize(name + "-cropscale", idealSize, ProcessImageOperation.CropScale)

object ScaledImageSize {
  case object Small extends ScaledImageSize("small", ImageSize(150, 150))
  case object Medium extends ScaledImageSize("medium", ImageSize(400, 400))
  case object Large extends ScaledImageSize("large", ImageSize(1000, 1000))
  case object XLarge extends ScaledImageSize("xlarge", ImageSize(1500, 1500))

  val allSizes: Seq[ScaledImageSize] = Seq(Small, Medium, Large, XLarge)
}

object CroppedImageSize {
  case object Small extends CroppedImageSize("small", ImageSize(150, 150))
}

object CropScaledImageSize {
  case object Tiny extends CropScaledImageSize("small", ImageSize(100, 100))
  case object Medium extends CropScaledImageSize("medium", ImageSize(200, 200))
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

@Singleton
class ImageCleanup @Inject() (
  clock: Clock
) extends Logging {
  // When temporary files are created, they can be registered with this class, which will
  // delete them after a certain amount of time has passed.
  private val images = new mutable.Queue[(DateTime, String)]()
  private val cleanupAfterMin = 15

  def cleanup(file: File): Unit = Try {
    synchronized { images.enqueue((clock.now, file.getAbsolutePath)) }
    purge()
  }

  private def purge(): Unit = {
    val shouldPop = images.length > 500 || images.headOption.exists(_._1.isBefore(clock.now.minusMinutes(cleanupAfterMin)))
    if (shouldPop) {
      synchronized {
        Try(images.dequeue()).map(_._2).toOption
      }.foreach { filename =>
        Try {
          val file = new File(filename)
          if (file.exists()) {
            file.delete()
          }
        } match {
          case Failure(ex) => log.info(s"[ImageCleanup] Couldn't delete file $filename: ${ex.toString}")
          case Success(_) =>
        }
        if (images.nonEmpty) { purge() }
      }
    }
  }
}
