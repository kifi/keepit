package com.keepit.common.pagepeeker

import com.ning.http.client.providers.netty.NettyResponse

import scala.concurrent._
import play.api.libs.ws.WS
import com.keepit.common.logging.Logging
import com.google.inject.{ Inject, Singleton }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.net.URLEncoder
import com.keepit.common.strings._
import com.keepit.common.store.{ ImageUtils, ImageSize }
import java.awt.image.BufferedImage
import scala.util.{ Failure, Success, Try }
import javax.imageio.ImageIO
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeNotifier, AirbrakeError }
import java.io.ByteArrayInputStream
import com.keepit.model.{ NormalizedURI, ImageFormat, ImageProvider, ImageInfo }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.time.Clock
import play.api.Play.current

case class ScreenshotConfig(imageCode: String, targetSizes: Seq[ImageSize])

case class PagePeekerImage(rawImage: BufferedImage, size: ImageSize) {
  implicit def toImageInfo(nUriId: Id[NormalizedURI], url: Option[String] = None): ImageInfo = ImageInfo(uriId = nUriId, url = url, caption = None, width = Some(size.width), height = Some(size.height), provider = Some(ImageProvider.PAGEPEEKER), format = Some(ImageFormat.JPG))
}

trait PagePeekerClient {
  def getScreenshotData(normalizedUri: NormalizedURI): Future[Option[Seq[PagePeekerImage]]]
}

@Singleton
class PagePeekerClientImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    clock: Clock,
    imageUtils: ImageUtils,
    airbrake: AirbrakeNotifier) extends PagePeekerClient with Logging {

  // The image below matches the blank image sometimes returned by PagePeeker
  // val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59).map(_.asInstanceOf[Byte])
  val screenshotConfig = ScreenshotConfig("c", Seq(ImageSize(1000, 560), ImageSize(500, 280), ImageSize(250, 140)))
  val linkedSize = ImageSize(500, 280) // which size to link to, by default; todo: user configurable

  val imageCode = "c"
  val code = "abf9cd2751"

  def screenshotUrl(url: String): String = screenshotUrl(screenshotConfig.imageCode, code, url)
  def screenshotUrl(sizeName: String, code: String, url: String): String =
    s"http://api.pagepeeker.com/v2/thumbs.php?size=$sizeName&code=$code&url=${URLEncoder.encode(url, UTF8)}&wait=60&refresh=1"

  override def getScreenshotData(normalizedUri: NormalizedURI): Future[Option[Seq[PagePeekerImage]]] = {
    val trace = new StackTrace()
    val url = normalizedUri.url
    WS.url(screenshotUrl(url)).withRequestTimeout(120000).get().map { response =>
      Option(response.underlying[NettyResponse].getHeader("X-PP-Error")) match {
        case Some("True") =>
          log.warn(s"Failed to take a screenshot of $url. Reported error from provider.")
          statsd.incrementOne(s"screenshot.fetch.fails", ONE_IN_TEN)
          None
        case _ =>

          val originalStream = response.underlying[NettyResponse].getResponseBodyAsStream
          val rawImageTry = Try { ImageIO.read(originalStream) }

          val resizedImages: Seq[Try[(ByteArrayInputStream, ImageSize)]] = screenshotConfig.targetSizes.map { size =>
            for {
              rawImage <- rawImageTry
              resized <- Try { imageUtils.resizeImageKeepProportions(rawImage, size) }
            } yield (resized._2, size)
          }

          val screenshots = resizedImages flatMap {
            case resizeAttempt =>
              resizeAttempt match {
                case Success((imageStream, size)) =>
                  statsd.incrementOne(s"screenshot.fetch.successes", ONE_IN_HUNDRED)
                  val rawImageOpt = try {
                    Option(ImageIO.read(imageStream))
                  } catch {
                    case ex: Throwable => {
                      airbrake.notify(AirbrakeError(
                        exception = trace.withCause(ex),
                        message = Some(s"Problem reading resized screenshot from $url")
                      ))
                      None
                    }
                  } finally {
                    imageStream.close()
                  }
                  rawImageOpt map { rawImage => PagePeekerImage(rawImage, size) }
                case Failure(ex) =>
                  statsd.incrementOne(s"screenshot.fetch.fails", ALWAYS)
                  ex match {
                    case e: java.lang.IllegalArgumentException =>
                      // This happens when the image stream is null, coming from javax.imageio.ImageIO
                      // todo(andrew): Excellent candidate for a persistent queue!
                      log.warn(s"null image for $url. Will retry later.")
                    case e: javax.imageio.IIOException =>
                      // This happens when the provider gave a corrupted jpeg.
                      // todo(andrew): Excellent candidate for a persistent queue!
                      log.warn(s"Provider gave invalid screenshot for $url. Will retry later.")
                    case _: Throwable =>
                      airbrake.notify(AirbrakeError(
                        exception = trace.withCause(ex),
                        message = Some(s"Problem resizing screenshot image from $url")
                      ))
                  }
                  None
              }
          }

          originalStream.close()
          // Return screenshots only if everything went well
          if (screenshots.length == screenshotConfig.targetSizes.length) Some(screenshots) else None
      }
    }
  }
}

class DevPagePeekerClient @Inject() extends PagePeekerClient {
  def getScreenshotData(normalizedUri: NormalizedURI): Future[Option[Seq[PagePeekerImage]]] = Future.successful(None)
}
