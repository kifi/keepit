package com.keepit.common.images

import java.awt.image.BufferedImage
import java.io.{ IOException, InputStream }
import java.security.GeneralSecurityException
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, StackTrace }
import com.keepit.common.logging.{ Access, AccessLog, Logging }
import com.keepit.common.net.URI
import com.keepit.common.store.ImageUtils
import com.ning.http.client.providers.netty.NettyResponse
import org.jboss.netty.channel.ConnectTimeoutException
import play.api.Play.current
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.WS

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[ImageFetcherImpl])
trait ImageFetcher {
  def fetchRawImage(url: URI): Future[Option[BufferedImage]]
}

@Singleton
class ImageFetcherImpl @Inject() (
    airbrake: AirbrakeNotifier,
    accessLog: AccessLog,
    imageUtils: ImageUtils) extends ImageFetcher with Logging {

  private def withInputStream[T, I <: java.io.InputStream](is: I)(f: I => T): T = {
    try {
      f(is)
    } finally {
      if (is != null) is.close()
    }
  }

  private def getBufferedImage(is: InputStream) = Try { imageUtils.forceRGB(ImageIO.read(is)) }

  override def fetchRawImage(url: URI): Future[Option[BufferedImage]] = {
    val validUri = URI.parse(url.toString()) match {
      case Success(goodUri) =>
        if (goodUri.host.exists(_.domain.exists(_.contains("_")))) {
          log.error(s"Url [$url] has a hostname which contains an underscore, our current http client would not like that!")
          None
        } else Some(goodUri)
      case Failure(e) =>
        log.error(s"Url [$url] parsing error, ignoring image", e)
        None
    }
    validUri match {
      case None => Future.successful(None) // ignoring
      case Some(uriObj) =>
        val trace = new StackTrace()
        val timer = accessLog.timer(Access.HTTP_OUT)

        WS.url(uriObj.toString()).withRequestTimeout(120000).get map { resp =>
          log.info(s"[fetchRawImage($url)] resp=${resp.statusText}")
          resp.status match {
            case Status.OK =>
              withInputStream(resp.underlying[NettyResponse].getResponseBodyAsStream) { is =>
                getBufferedImage(is) match {
                  case Failure(ex) =>
                    log.error(s"Failed to process image: ($url)")
                    None
                  case Success(rawImage) =>
                    timer.done(url = uriObj.toString(), statusCode = resp.status)
                    Some(rawImage)
                }
              }
            case _ =>
              log.error(s"[fetchRawImage($url)] Failed to retrieve image. Response: ${resp.statusText}")
              timer.done(url = uriObj.toString(), statusCode = resp.status, error = resp.statusText)
              None
          }
        } recover {
          case e @ (
            _: TimeoutException |
            _: ConnectTimeoutException |
            _: GeneralSecurityException |
            _: IOException) => {
            timer.done(url = url.toString(), error = e.toString)
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
}
