package com.keepit.common.store

import java.net.URLEncoder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Await, Future, Promise}
import scala.util.Failure
import scala.util.Success
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.{StackTrace, AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.strings.UTF8
import com.keepit.common.time.Clock
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.model.NormalizedURI
import play.api.libs.ws.WS
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
import java.io.{InputStream, ByteArrayInputStream}
import com.keepit.common.akka.SafeFuture
import collection.JavaConversions._
import scala.concurrent.duration._

trait S3ScreenshotStore {
  def config: S3ImageConfig
  val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59).map(_.asInstanceOf[Byte])

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String]
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String]
  def getImageInfos(normalizedUri: NormalizedURI):Future[Seq[ImageInfo]]
  def processImage(uri:NormalizedURI, imageInfo:ImageInfo):Future[Boolean]
  def asyncGetImageUrl(uri:NormalizedURI):Future[Option[String]]
  def getImageUrl(normalizedUri: NormalizedURI):Option[String]
  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean]
}

case class ScreenshotConfig(imageCode: String, targetSizes: Seq[ImageSize])

case class ImageInfo(
  url:String,
  caption:Option[String],
  height:Option[Int],
  width:Option[Int],
  size:Option[Int])

object ImageInfo {
  implicit val format = (
      (__ \ 'url).format[String] and
      (__ \ 'caption).formatNullable[String] and
      (__ \ 'height).formatNullable[Int] and
      (__ \ 'weight).formatNullable[Int] and
      (__ \ 'size).formatNullable[Int]
    )(ImageInfo.apply _, unlift(ImageInfo.unapply))
}

trait SafetyInfo {
  def safe:Option[Boolean]
}

trait PageInfo {
  def originalUrl:Option[String]
  def url:Option[String]
  def title:Option[String]
  def description:Option[String]
  def content:Option[String]
  def favicon_url:Option[String]
  def images:Seq[ImageInfo]
}

case class EmbedlyExtractResponse(
  originalUrl:Option[String],
  url:Option[String],
  title:Option[String],
  description:Option[String],
  content:Option[String],
  safe:Option[Boolean],
  favicon_url:Option[String],
  images:Seq[ImageInfo]) extends PageInfo with SafetyInfo

object EmbedlyExtractResponse {
  val EMPTY = EmbedlyExtractResponse(None, None, None, None, None, None, None, Seq.empty[ImageInfo])

  implicit val format = (
    (__ \ 'original_url).formatNullable[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[ImageInfo]]
  )(EmbedlyExtractResponse.apply _, unlift(EmbedlyExtractResponse.unapply))
}

class S3ScreenshotStoreImpl(
    s3Client: AmazonS3,
    shoeboxServiceClient: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    val config: S3ImageConfig
  ) extends S3ScreenshotStore with Logging {

  val screenshotConfig = ScreenshotConfig("c", Seq(ImageSize(1000, 560), ImageSize(500, 280), ImageSize(250, 140)))
  val linkedSize = ImageSize(500, 280) // which size to link to, by default; todo: user configurable

  val code = "abf9cd2751"

  def screenshotUrl(url: String): String = screenshotUrl(screenshotConfig.imageCode, code, url)
  def screenshotUrl(sizeName: String, code: String, url: String): String =
    s"http://api.pagepeeker.com/v2/thumbs.php?size=$sizeName&code=$code&url=${URLEncoder.encode(url, UTF8)}&wait=60&refresh=1"

  val embedlyDefaultKey = "e46ecae2611d4cb29342fddb0e666a29"
  val embedlyKey = (if (Play.maybeApplication.isDefined) Play.configuration.getString("embedly.key") else None) getOrElse embedlyDefaultKey
  def embedlyUrl(url: String, key: String = embedlyKey) = s"http://api.embed.ly/1/extract?key=$key&url=${URLEncoder.encode(url, UTF8)}"

  def mkImgBucket(extId: ExternalId[NormalizedURI], providerIdx:Int = 0) = s"images/$extId/$providerIdx"
  def mkImgKey(id: ExternalId[NormalizedURI], size:ImageSize) = s"${mkImgBucket(id)}/${size.width}x${size.height}.jpg"
  def mkImgUrl(id: ExternalId[NormalizedURI], protocolDefault: Option[String] = None): Option[String] = {
    val s = s"${config.cdnBase}/${mkImgKey(id, linkedSize)}"
    URI.parse(s) match {
      case Success(uri) =>
        Some(URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString)
      case Failure(t) =>
        airbrake.notify(s"Failed to parse $s; Exception: $t; Cause: ${t.getCause}", t)
        None
    }
  }

  private def getPageInfo(url:String):Future[Option[PageInfo]] = {
    WS.url(url).withRequestTimeout(120000).get().map { resp =>
      log.info(s"[getPageInfo($url)] resp.status=${resp.statusText} body=${resp.body}")
      resp.status match {
        case Status.OK =>
          val pageInfo = Json.fromJson[EmbedlyExtractResponse](resp.json).asOpt
          log.info(s"[getPageInfo($url)] pageInfo=$pageInfo")
          pageInfo
        case _ =>
          airbrake.notify(s"[getExtractResponse($url)] ERROR from provider: status=${resp.status} body=${resp.body}")
          None
      }
    }
  }

  def getImageInfos(normalizedUri: NormalizedURI):Future[Seq[ImageInfo]] = {
    getPageInfo(embedlyUrl(normalizedUri.url)) map { resp =>
      val imgInfos = resp map (_.images) getOrElse Seq.empty[ImageInfo]
      log.info(s"[getImageInfos(${normalizedUri.url})] ${imgInfos.mkString(",")}")
      imgInfos
    }
  }

  def asyncGetImageUrl(uri:NormalizedURI):Future[Option[String]] = {
    if (config.isLocal) Future.successful(None)
    else {
      // todo: check table
      updateImage(uri) map { res =>
        if (res) mkImgUrl(uri.externalId) else None
      }
    }
  }

  def getImageUrl(uri: NormalizedURI):Option[String] = Await.result(asyncGetImageUrl(uri), 5 minutes)

  private def updateImage(uri:NormalizedURI):Future[Boolean] = {
    getImageInfos(uri) flatMap { infos =>
      infos.headOption match {
        case Some(info) => processImage(uri, info)
        case None => Future.successful(false)
      }
    }
  }

  def processImage(uri:NormalizedURI, imageInfo:ImageInfo):Future[Boolean] = {
    val trace = new StackTrace()
    val url = imageInfo.url
    val future = WS.url(url).withRequestTimeout(120000).get.map { resp =>
      log.info(s"[processImage(${uri.id},${imageInfo.url})] resp=${resp.statusText}")
      resp.status match {
        case Status.OK =>
          resizeAndPersist(trace, url, uri, resp.getAHCResponse.getResponseBodyAsStream)
        case _ =>
          log.error(s"[processImage(${uri.id},${imageInfo.url})] Failed to retrieve image. Response: ${resp.statusText}")
          false
      }
    }
    // todo: update table
    future
  }

  private def resize(originalStream:InputStream):Seq[Try[(Int, ByteArrayInputStream, ImageSize)]] = {
    val rawImageTry = Try { ImageIO.read(originalStream) }
    val resizedImages:Seq[Try[(Int, ByteArrayInputStream, ImageSize)]] = screenshotConfig.targetSizes.map { size =>
      for {
        rawImage <- rawImageTry
        resized <- Try {
          ImageUtils.resizeImageKeepProportions(rawImage, size)
        }
      } yield (resized._1, resized._2, size)
    }
    resizedImages
  }

  private def resizeAndPersist(trace:StackTrace, url:String, uri:NormalizedURI, originalStream:InputStream) = {
    val resizedImages = resize(originalStream)
    val storedObjects = resizedImages map { case resizeAttempt =>
      resizeAttempt match {
        case Success((contentLength, imageStream, size)) =>
          Statsd.increment(s"image.fetch.successes")
          val om = new ObjectMetadata()
          om.setContentType("image/jpeg")
          om.setContentLength(contentLength)
          om.setCacheControl("public, max-age=1800")
          val key = mkImgKey(uri.externalId, size)
          val s3obj = s3Client.putObject(config.bucketName, key, imageStream, om)
          log.info(s"Uploading image of $url to S3 key $key")
          imageStream.close
          Some(s3obj)
        case Failure(ex) =>
          Statsd.increment(s"image.fetch.fails")
          ex match {
            case e: java.lang.IllegalArgumentException => log.warn(s"null image for $url. Will retry later.")
            case e: javax.imageio.IIOException => log.warn(s"Provider gave invalid image for $url. Will retry later.")
            case _ => airbrake.notify(AirbrakeError(trace.withCause(ex),Some(s"Problem resizing image from $url")))
          }
          None
      }
    }
    originalStream.close()
    storedObjects.forall(_.nonEmpty)
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
