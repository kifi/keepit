
package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.model._
import com.keepit.scraper.store.UriImageStore
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[UriImageCommanderImpl])
trait UriImageCommander {
  // Fetch, resize, persist to S3:
  def processRemoteImage(remoteImageUrl: String): Future[Either[Seq[ImageProcessState.UploadedImage], ImageStoreFailure]]
}

case class UriImage(imagePath: String)

object UriImageSizes {
  val scaleSizes = ScaledImageSize.allSizes
  val cropSizes = Seq.empty
}

@Singleton
class UriImageCommanderImpl @Inject() (
    uriImageStore: UriImageStore,
    photoshop: Photoshop,
    val webService: WebService) extends UriImageCommander with ProcessedImageHelper with Logging {

  def processRemoteImage(remoteImageUrl: String): Future[Either[Seq[ImageProcessState.UploadedImage], ImageStoreFailure]] = {
    fetchAndHashRemoteImage(remoteImageUrl).flatMap {
      case Right(originalImage) =>
        buildPersistSet(originalImage, "i", UriImageSizes.scaleSizes, UriImageSizes.cropSizes)(photoshop) match {
          case Right(toPersist) =>
            val uploads = toPersist.map { image =>
              log.info(s"[uic] Persisting ${image.key} (${image.bytes} B)")
              uriImageStore.put(image.key.path, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
                ImageProcessState.UploadedImage(image.key, image.format, image.image, image.processOperation)
              }
            }

            Future.sequence(uploads).map { results =>
              val sortedBySize = results.toList.sortBy(img => img.image.getWidth + img.image.getHeight).reverse // biggest first
              Left(sortedBySize)
            }.recover {
              case ex: Throwable =>
                Right(ImageProcessState.CDNUploadFailed(ex))
            }
          case Left(failure) =>
            Future.successful(Right(failure))
        }
      case Left(failure) => Future.successful(Right(failure))
    }
  }

}
