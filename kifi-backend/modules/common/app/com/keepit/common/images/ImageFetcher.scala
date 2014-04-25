package com.keepit.common.images

import java.io.InputStream
import scala.util.{Success, Failure, Try}
import com.keepit.common.store.ImageUtils
import javax.imageio.ImageIO
import scala.concurrent.Future
import java.awt.image.BufferedImage
import play.api.libs.ws.WS
import play.api.http.Status
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[ImageFetcherImpl])
trait ImageFetcher {
  def fetchRawImage(url: String): Future[Option[BufferedImage]]
}

@Singleton
class ImageFetcherImpl @Inject() extends ImageFetcher with Logging {

  private def withInputStream[T, I <: java.io.InputStream](is:I)(f:I => T):T = {
    try {
      f(is)
    } finally {
      if (is != null) is.close
    }
  }

  private def getBufferedImage(is:InputStream) = Try { ImageUtils.forceRGB(ImageIO.read(is)) }

  override def fetchRawImage(url: String): Future[Option[BufferedImage]] = {
    WS.url(url).withRequestTimeout(120000).get map { resp =>
      log.info(s"[fetchRawImage(${url})] resp=${resp.statusText}")
      resp.status match {
        case Status.OK =>
          withInputStream(resp.getAHCResponse.getResponseBodyAsStream) { is =>
            getBufferedImage(is) match {
              case Failure(ex) =>
                log.error(s"Failed to process image: (${url})")
                None
              case Success(rawImage) =>
                Some(rawImage)
            }
          }
        case _ =>
          log.error(s"[fetchRawImage(${url})] Failed to retrieve image. Response: ${resp.statusText}")
          None
      }
    }
  }
}
