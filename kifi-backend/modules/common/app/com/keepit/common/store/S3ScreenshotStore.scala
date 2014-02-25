package com.keepit.common.store

import java.net.URLEncoder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectResult
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

trait S3ScreenshotStore {
  def config: S3ImageConfig
  val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59).map(_.asInstanceOf[Byte])

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String]
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String]
  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean]
}

case class ScreenshotConfig(imageCode: String, targetSizes: Seq[ImageSize])

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
