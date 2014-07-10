package com.keepit.common.images

import java.io.InputStream
import scala.util.{ Success, Failure, Try }
import com.keepit.common.store.ImageUtils
import javax.imageio.ImageIO
import scala.concurrent.Future
import java.awt.image.BufferedImage
import play.api.libs.ws.WS
import play.api.http.Status
import com.keepit.common.logging.{ Access, AccessLog, Logging }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeNotifier }
import java.security.cert.CertificateExpiredException
import java.nio.channels.ClosedChannelException
import java.net.{ URISyntaxException, ConnectException }
import com.keepit.common.net.URI
import org.jboss.netty.channel.ConnectTimeoutException
import java.security.GeneralSecurityException
import java.io.IOException
import java.util.concurrent.TimeoutException

@ImplementedBy(classOf[ImageFetcherImpl])
trait ImageFetcher {
  def fetchRawImage(url: String): Future[Option[BufferedImage]]
}

@Singleton
class ImageFetcherImpl @Inject() (
    airbrake: AirbrakeNotifier,
    accessLog: AccessLog) extends ImageFetcher with Logging {

  private def withInputStream[T, I <: java.io.InputStream](is: I)(f: I => T): T = {
    try {
      f(is)
    } finally {
      if (is != null) is.close()
    }
  }

  private def getBufferedImage(is: InputStream) = Try { ImageUtils.forceRGB(ImageIO.read(is)) }

  override def fetchRawImage(url: String): Future[Option[BufferedImage]] = {
    val trace = new StackTrace()
    val timer = accessLog.timer(Access.HTTP_OUT)
    val uriObj = URI.parse(url) match {
      case Success(uriObj) => uriObj
      case Failure(e) => {
        log.error(s"Url [$url] parsing error, ignoring image", e)
        return Future.successful(None) //just ignore
      }
    }
    WS.url(uriObj.toString).withRequestTimeout(120000).get map { resp =>
      log.info(s"[fetchRawImage($url)] resp=${resp.statusText}")
      resp.status match {
        case Status.OK =>
          withInputStream(resp.getAHCResponse.getResponseBodyAsStream) { is =>
            getBufferedImage(is) match {
              case Failure(ex) =>
                log.error(s"Failed to process image: ($url)")
                None
              case Success(rawImage) =>
                timer.done(url = url, statusCode = resp.status)
                Some(rawImage)
            }
          }
        case _ =>
          log.error(s"[fetchRawImage($url)] Failed to retrieve image. Response: ${resp.statusText}")
          timer.done(url = url, statusCode = resp.status, error = resp.statusText)
          None
      }
    } recover {
      case e @ (
        _: TimeoutException |
        _: ConnectTimeoutException |
        _: GeneralSecurityException |
        _: IOException) => {
        timer.done(url = url, error = e.toString)
        log.warn(s"Can't connect to $url, next time it may work", trace.withCause(e))
        None
      }
      case t: Throwable => {
        airbrake.notify(s"Error fetching image with url $url", trace.withCause(t))
        None
      }
    }
  }
}
