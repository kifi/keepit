package com.keepit.common.store

import java.net.URLEncoder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Await, Future, Promise}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, ObjectMetadata}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.healthcheck.{SystemAdminMailSender, StackTrace, AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.strings.UTF8
import com.keepit.common.time.Clock
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.model._
import play.api.libs.ws.{Response, WS}
import scala.util.Try
import play.modules.statsd.api.Statsd
import javax.imageio.ImageIO
import com.keepit.common.net.URI
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.Play
import play.api.Play.current
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.functional.syntax._
import java.io.{ByteArrayOutputStream, InputStream, ByteArrayInputStream}
import com.keepit.common.akka.SafeFuture
import collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Failure
import scala.Some
import scala.util.Success
import com.keepit.common.concurrent.{RetryFuture, ExecutionContext}
import java.awt.image.BufferedImage
import scala.collection.mutable.ArrayBuffer
import org.apache.commons.lang3.RandomStringUtils
import com.keepit.common.mail.{EmailAddresses, ElectronicMail}
import com.keepit.common.service.RequestConsolidator
import scala.concurrent.duration._

trait S3ScreenshotStore {
  def config: S3ImageConfig
  val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59).map(_.asInstanceOf[Byte])

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String]
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String]

  def getImageInfos(normalizedUri: NormalizedURI):Future[Seq[ImageInfo]]
  def asyncGetImageUrl(uri:NormalizedURI, pageInfoOpt:Option[PageInfo]):Future[Option[String]]
  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean]
}

case class ScreenshotConfig(imageCode: String, targetSizes: Seq[ImageSize])

case class EmbedlyImage(
  url:String,
  caption:Option[String] = None,
  width:Option[Int]      = None,
  height:Option[Int]     = None,
  size:Option[Int]       = None) extends ImageGenericInfo {
  implicit def toImageInfo(nuriId:Id[NormalizedURI]):ImageInfo = ImageInfo(uriId = nuriId, url = this.url, caption = this.caption, width = this.width, height = this.height, size = this.size)
}

object EmbedlyImage {
  implicit val format = (
    (__ \ 'url).format[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int]
  )(EmbedlyImage.apply _, unlift(EmbedlyImage.unapply))
}

case class EmbedlyExtractResponse(
  originalUrl:String,
  url:Option[String],
  title:Option[String],
  description:Option[String],
  content:Option[String],
  safe:Option[Boolean],
  faviconUrl:Option[String],
  images:Seq[EmbedlyImage]) extends PageGenericInfo with PageSafetyInfo with PageMediaInfo {
  implicit def toPageInfo(nuriId:Id[NormalizedURI]):PageInfo =
    PageInfo(
      id = None,
      uriId = nuriId,
      description = this.description,
      safe = this.safe,
      faviconUrl = this.faviconUrl
    )
}

object EmbedlyExtractResponse {
  val EMPTY = EmbedlyExtractResponse("", None, None, None, None, None, None, Seq.empty[EmbedlyImage])

  implicit val format = (
    (__ \ 'original_url).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[EmbedlyImage]]
  )(EmbedlyExtractResponse.apply _, unlift(EmbedlyExtractResponse.unapply))
}

class S3ScreenshotStoreImpl(
    override val s3Client: AmazonS3,
    shoeboxServiceClient: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    systemAdminMailSender: SystemAdminMailSender,
    val config: S3ImageConfig
  ) extends S3ScreenshotStore with S3Helper with Logging {

  val screenshotConfig = ScreenshotConfig("c", Seq(ImageSize(1000, 560), ImageSize(500, 280), ImageSize(250, 140)))
  val linkedSize = ImageSize(500, 280) // which size to link to, by default; todo: user configurable

  val code = "abf9cd2751"

  def screenshotUrl(url: String): String = screenshotUrl(screenshotConfig.imageCode, code, url)
  def screenshotUrl(sizeName: String, code: String, url: String): String =
    s"http://api.pagepeeker.com/v2/thumbs.php?size=$sizeName&code=$code&url=${URLEncoder.encode(url, UTF8)}&wait=60&refresh=1"

  val imageSizes = Seq(ImageSize(500, 280))
  val embedlyKey = (if (Play.maybeApplication.isDefined) Play.configuration.getString("embedly.key") else None) get
  val embedlyEnabled = (if (Play.maybeApplication.isDefined) Play.configuration.getBoolean("embedly.enabled") else None) get
  def embedlyUrl(url: String, key: String = embedlyKey) = s"http://api.embed.ly/1/extract?key=$key&url=${URLEncoder.encode(url, UTF8)}"

  def mkImgBucket(extId: ExternalId[NormalizedURI], providerIdx:Int = 0) = s"images/$extId/$providerIdx"
  def mkImgKey(id: ExternalId[NormalizedURI], size:ImageSize) = s"${mkImgBucket(id)}/${size.width}x${size.height}.jpg"
  def mkImgKey(id: ExternalId[NormalizedURI], nameOpt:Option[String], size:ImageSize):String = {
    nameOpt match {
      case Some(n) => s"${mkImgBucket(id)}/${n}.jpg"
      case None => mkImgKey(id, size)
    }
  }
  def mkImgUrl(id: ExternalId[NormalizedURI], name:Option[String], protocolDefault: Option[String] = None): Option[String] = {
    val s = s"${config.cdnBase}/${mkImgKey(id, name, linkedSize)}"
    URI.parse(s) match {
      case Success(uri) =>
        Some(URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString)
      case Failure(t) =>
        airbrake.notify(s"Failed to parse $s; Exception: $t; Cause: ${t.getCause}", t)
        None
    }
  }

  private val fetchPageInfoConsolidator = new RequestConsolidator[String,Option[EmbedlyExtractResponse]](2 minutes)

  private def getPageInfo(url:String):Future[Option[EmbedlyExtractResponse]] = {
    fetchPageInfoConsolidator(url) { urlString =>
      val future = WS.url(url).withRequestTimeout(120000).get()
      future map { resp =>
        log.info(s"[getPageInfo($url)] resp.status=${resp.statusText} body=${resp.body} json=${Json.prettyPrint(resp.json)}")
        resp.status match {
          case Status.OK =>
            val pageInfo = Json.fromJson[EmbedlyExtractResponse](resp.json).asOpt
            log.info(s"[getPageInfo($url)] pageInfo=$pageInfo")
            pageInfo
          case _ =>
            val msg = s"[getExtractResponse($url)] ERROR from provider: status=${resp.status} body=${resp.body}"
            log.error(msg)
            systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.RAY, to = List(EmailAddresses.RAY),
              subject = s"S3ScreenshotStore.getPageInfo($url)",
              htmlBody = s"$msg\n${Thread.currentThread.getStackTrace.mkString("\n")}",
              category = NotificationCategory.System.ADMIN))
            // todo(ray): retry and/or put on queue
            None
        }
      } recover { case t:Throwable =>
        val msg = s"[getExtractResponse($url)] Caught exception $t; cause=${t.getCause}"
        log.error(msg)
        systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.RAY, to = List(EmailAddresses.RAY),
          subject = s"S3ScreenshotStore.getPageInfo($url)",
          htmlBody = s"$msg\n${Thread.currentThread.getStackTrace.mkString("\n")}",
          category = NotificationCategory.System.ADMIN))
        None
      }
    }
  }

  def getImageInfos(uri: NormalizedURI):Future[Seq[ImageInfo]] = {
    getPageInfo(embedlyUrl(uri.url)) map { resp =>
      val imgInfos = resp map (_.images.map(_.toImageInfo(uri.id.get))) getOrElse Seq.empty[ImageInfo]
      log.info(s"[getImageInfos(${uri.url})] ${imgInfos.mkString(",")}")
      imgInfos
    }
  }

  def asyncGetImageUrl(uri:NormalizedURI, pageInfoOpt:Option[PageInfo]):Future[Option[String]] = {
    log.info(s"[asyncGetImageUrl] uri=$uri pageInfo=$pageInfoOpt")
    if (config.isLocal || !embedlyEnabled) Future.successful(None)
    else {
      pageInfoOpt match {
        case None =>
          fetchAndUpdatePageInfo(uri) map { case (pageOpt, imgOpt) =>
            imgOpt flatMap { imgInfo =>
              mkImgUrl(uri.externalId, imgInfo.name)
            }
          }
        case Some(pageInfo) =>
          pageInfo.imageInfoId match {
            case Some(imgInfoId) =>
              shoeboxServiceClient.getImageInfo(imgInfoId) map { imgInfo =>
                mkImgUrl(uri.externalId, imgInfo.name)
              }
            case _ => Future.successful(None) // no image
          }
      }
    }
  }

  private def fetchAndUpdatePageInfo(uri:NormalizedURI):Future[(Option[PageInfo],Option[ImageInfo])] = {
    log.info(s"[fetchAndUpdatePageInfo(${uri.id})] url=${uri.url}")
    getPageInfo(embedlyUrl(uri.url)) flatMap { pageInfoOpt =>
      pageInfoOpt match {
        case Some(pageInfo) =>
          val imgOptF = pageInfo.images.headOption match {
            case Some(embedlyImage) =>
              fetchAndUpdateImageInfo(uri, embedlyImage.toImageInfo(uri.id.get)) recoverWith {
                case t:Throwable =>
                  log.error(s"Failed to process image ($embedlyImage), Exception:$t; cause=${t.getCause}")
                  findAltImage(pageInfo.images.drop(1).take(3), uri)
              }
            case None => // no image
              Future.successful(None)
          }
          imgOptF flatMap { imgOpt =>
            shoeboxServiceClient.savePageInfo(pageInfo.toPageInfo(uri.id.get).copy(imageInfoId = imgOpt.flatMap(_.id))) map { savedPageInfo =>
              log.info(s"[fetchAndUpdatePageInfo(${uri.id},${uri.url})] saved pageInfo=${savedPageInfo} imageInfo=${imgOpt}")
              (Option(savedPageInfo), imgOpt)
            }
          }
        case None => // todo: track failure reason (may airbrake)
          Future.failed(new InternalError(s"Failed to retrieve pageInfo for (${uri.id},${uri.url}) embedlyUrl=${embedlyUrl(uri.url)}"))
      }
    }
  }

  private def findAltImage(altImages: Seq[EmbedlyImage], uri: NormalizedURI): Future[Option[ImageInfo]] = {
    log.info(s"[findAltImage(${uri.id},${uri.url})] attempt to process (alt) #(${altImages.length}) images: ${altImages.mkString(",")}")
    val futures = altImages.map { img =>
      fetchAndUpdateImageInfo(uri, img.toImageInfo(uri.id.get)) recover {
        case t: Throwable =>
          log.error(s"Failed to process (alt) image ($img), Exception:$t; cause=${t.getCause}")
          None
      }
    }
    val resF = Future.sequence(futures) map { res => // optimize if needed
      val hdOpt = res.collect { case (imgOpt) if imgOpt.nonEmpty => imgOpt.get }.headOption
      hdOpt match {
        case None =>
          log.error(s"Failed to process alternative images")
          // todo(ray): favicon
          None
        case Some(altImg) =>
          log.info(s"[fetchAndUpdatePageInfo(${uri.id},${uri.url})] successfully found (alt) image=${altImg}")
          Option(altImg)
      }
    }
    resF
  }

  private def withInputStream[T, I <: java.io.InputStream](is:I)(f:I => T):T = {
    try {
      f(is)
    } finally {
      if (is != null) is.close
    }
  }
  private def getBufferedImage(is:InputStream) = Try { ImageUtils.forceRGB(ImageIO.read(is)) }

  val ORIGINAL_TOKEN = "0"

  private def uploadOriginal(uri:NormalizedURI, url:String, imageInfo:ImageInfo, rawImage:BufferedImage) = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close

    val key = s"images/${uri.externalId}/0/${ORIGINAL_TOKEN}.jpg"
    uploadImage(key, uri, url, bytes.length, ImageSize(rawImage.getWidth, rawImage.getHeight), new ByteArrayInputStream(bytes))
  }

  private def fetchAndUpdateImageInfo(uri:NormalizedURI, imageInfo:ImageInfo):Future[Option[ImageInfo]] = {
    val trace = new StackTrace()
    val url = imageInfo.url
    val future = WS.url(url).withRequestTimeout(120000).get flatMap { resp =>
      log.info(s"[processImage(${uri.id},${url})] resp=${resp.statusText}")
      resp.status match {
        case Status.OK =>
          withInputStream(resp.getAHCResponse.getResponseBodyAsStream) { is =>
            getBufferedImage(is) match {
              case Failure(ex) =>
                log.error(s"Failed to process image: (${uri.id},${imageInfo.url})")
                Future.failed(ex) // image no good
              case Success(rawImage) =>
                uploadOriginal(uri, url, imageInfo, rawImage) match {
                  case Failure(t) =>
                    log.error(s"Failed to upload origin image to S3 for (${uri.id},${url})")
                    Future.failed(t) // todo(ray): recover
                  case Success(origRes) => {
                    log.info(s"[processImage(${uri.id},${imageInfo.url})] successfully loaded original image to S3; res=${origRes}")
                    val origF = shoeboxServiceClient.saveImageInfo(imageInfo.copy(name = Some(ORIGINAL_TOKEN))) // async
                    val resizeRes = resizeAndUpload(trace, url, uri, rawImage)
                    val resSeqF = resizeRes.collect { case (trRes) if (trRes.isSuccess && trRes.get._4.isSuccess) =>
                      val (imgSize, token, contentLen, s3Tr) = trRes.get
                      log.info(s"[processImage(${uri.id},${imageInfo.url})] successfully resized (w=${imgSize.width},h=${imgSize.height},contentLen=${contentLen}) & uploaded image to s3 (token=$token); res=${s3Tr}")
                      shoeboxServiceClient.saveImageInfo(imageInfo.copy(name = Some(token), width = Some(imgSize.width), height = Some(imgSize.height), size = Some(contentLen))) // async
                    }

                    if (resSeqF.isEmpty) {
                      log.error(s"Failed to resize & upload images for (${uri.id},${url}")
                      origF map { Option(_) } // return original
                    } else {
                      val futSeq = Future.sequence(resSeqF)
                      futSeq map { _.headOption } // return first (& only) resized
                    }
                  }
                }
            }
          }
        case _ =>
          log.error(s"[processImage(${uri.id},${url})] Failed to retrieve image. Response: ${resp.statusText}")
          Future.failed(new InternalError(s"Failed to retrieve image (${uri.id},${url}); response: ${resp.statusText}")) // could not get the image
      }
    }
    future
  }

  private def resize(rawImage:BufferedImage):Seq[Try[(Int, ByteArrayInputStream, ImageSize)]] = {
    val resizedImages:Seq[Try[(Int, ByteArrayInputStream, ImageSize)]] = imageSizes.map { size =>
      Try {
        val res = ImageUtils.resizeImageKeepProportions(rawImage, size)
        (res._1, res._2, size)
      }
    }
    resizedImages
  }

  private def uploadImage(key:String, uri:NormalizedURI, url:String, contentLength:Int, size:ImageSize, imageStream:ByteArrayInputStream) = Try {
    val om = new ObjectMetadata()
    om.setContentType("image/jpeg")
    om.setContentLength(contentLength)
    om.setCacheControl("public, max-age=1800")
    val s3obj = s3Client.putObject(config.bucketName, key, imageStream, om) // todo: retry and/or put on queue
    log.info(s"Uploading image of ${url} (len=$contentLength,size=$size) to S3 key $key; result=$s3obj")
    s3obj
  }

  private def resizeAndUpload(trace:StackTrace, url:String, uri:NormalizedURI, rawImage:BufferedImage):Seq[Try[(ImageSize, String, Int, Try[PutObjectResult])]] = {
    val resizedImages = resize(rawImage)
    val trRes = resizedImages map { case resizeAttempt =>
      resizeAttempt match {
        case Success((contentLength, imageStream, imageSize)) =>
          Statsd.increment(s"image.fetch.successes")
          val token = RandomStringUtils.randomAlphanumeric(5)
          val key = mkImgKey(uri.externalId, Some(token), imageSize)
          val s3resTr = withInputStream(imageStream) { is =>
            uploadImage(key, uri, url, contentLength, imageSize, is)
          }
          Success(imageSize, token, contentLength, s3resTr)
        case Failure(ex) =>
          Statsd.increment(s"image.fetch.fails")
          ex match {
            case e: java.lang.IllegalArgumentException => log.warn(s"null image for $url. Will retry later.")
            case e: javax.imageio.IIOException => log.warn(s"Provider gave invalid image for $url. Will retry later.")
            case _ => airbrake.notify(AirbrakeError(trace.withCause(ex),Some(s"Problem resizing image from $url")))
          }
          Failure(ex) // can just propagate it
      }
    }
    trRes
  }

  def urlByExternalId(extNormalizedURIId: ExternalId[NormalizedURI], protocolDefault: Option[String] = None): String = {
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(extNormalizedURIId, linkedSize)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(extNormId: ExternalId[NormalizedURI], size: ImageSize): String =
    s"screenshot/$extNormId/${size.width}x${size.height}.jpg"

  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] =
    normalizedUriOpt.flatMap(getScreenshotUrl)

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = {
    if (config.isLocal) {
      None
    } else {
      normalizedUri.screenshotUpdatedAt match {
        case Some(updatedAt) =>
          Some(urlByExternalId(normalizedUri.externalId))
        case None =>
          updatePicture(normalizedUri)
          None
      }
    }
  }

  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean] = {
    val trace = new StackTrace()
    if (config.isLocal) {
      Promise.successful(true).future
    } else {
      val url = normalizedUri.url
      val externalId = normalizedUri.externalId
      val future = WS.url(screenshotUrl(url)).withRequestTimeout(120000).get().map { response =>
        Option(response.ahcResponse.getHeader("X-PP-Error")) match {
          case Some("True") =>
            log.warn(s"Failed to take a screenshot of $url. Reported error from provider.")
            Statsd.increment(s"screenshot.fetch.fails")
            false
          case _ =>

            val originalStream = response.getAHCResponse.getResponseBodyAsStream
            val rawImageTry = Try { ImageIO.read(originalStream) }

            val resizedImages = screenshotConfig.targetSizes.map { size =>
              for {
                rawImage <- rawImageTry
                resized <- Try { ImageUtils.resizeImageKeepProportions(rawImage, size) }
              } yield (resized._1, resized._2, size)
            }

            val storedObjects = resizedImages map { case resizeAttempt =>
              resizeAttempt match {
                case Success((contentLength, imageStream, size)) =>
                  Statsd.increment(s"screenshot.fetch.successes")

                  val om = new ObjectMetadata()
                  om.setContentType("image/jpeg")
                  om.setContentLength(contentLength)
                  om.setCacheControl("public, max-age=1800")
                  val key = keyByExternalId(externalId, size)
                  val s3obj = s3Client.putObject(config.bucketName, key, imageStream, om)
                  log.info(s"Uploading screenshot of $url to S3 key $key")

                  imageStream.close
                  Some(s3obj)
                case Failure(ex) =>
                  Statsd.increment(s"screenshot.fetch.fails")
                  ex match {
                    case e: java.lang.IllegalArgumentException =>
                      // This happens when the image stream is null, coming from javax.imageio.ImageIO
                      // todo(andrew): Excellent candidate for a persistent queue!
                      log.warn(s"null image for $url. Will retry later.")
                    case e: javax.imageio.IIOException =>
                      // This happens when the provider gave a corrupted jpeg.
                      // todo(andrew): Excellent candidate for a persistent queue!
                      log.warn(s"Provider gave invalid screenshot for $url. Will retry later.")
                    case _ =>
                      airbrake.notify(AirbrakeError(
                        exception = trace.withCause(ex),
                        message = Some(s"Problem resizing screenshot image from $url")
                      ))
                  }
                  None
              }
            }

            originalStream.close()

            storedObjects.forall(_.nonEmpty)
        }
      }
      future onComplete {
        case Success(result) =>
          if (result && normalizedUri.id.nonEmpty) {
            try {
              shoeboxServiceClient.updateNormalizedURI(uriId = normalizedUri.id.get, screenshotUpdatedAt = Some(clock.now))
            } catch {
              case ex: Throwable =>
                airbrake.notify(AirbrakeError(
                  exception = trace.withCause(ex),
                  message = Some(s"Failed to update normalized uri ($url) to S3")
                ))
            }
          }
        case Failure(e) =>
          airbrake.notify(AirbrakeError(
            exception = trace.withCause(e),
            message = Some(s"Failed to upload url screenshot ($url) to S3")
          ))
      }
      future
    }
  }
}
