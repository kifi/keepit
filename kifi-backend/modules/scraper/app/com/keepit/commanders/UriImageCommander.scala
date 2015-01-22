
package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.images.Photoshop
import com.keepit.common.logging.Logging
import com.keepit.common.net.WebService
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.model._
import com.keepit.scraper.store.UriImageStore
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[UriImageCommanderImpl])
trait UriImageCommander {
  def getUrl(libraryImage: UriImage): String

  // Fetch, resize, persist to S3:
  def processRemoteImage(remoteImageUrl: String): Future[Either[Seq[ImageProcessState.UploadedImage], ImageStoreFailure]]
}

case class UriImage(imagePath: String)

@Singleton
class UriImageCommanderImpl @Inject() (
    uriImageStore: UriImageStore,
    s3ImageConfig: S3ImageConfig,
    photoshop: Photoshop,
    val webService: WebService) extends UriImageCommander with ProcessedImageHelper with Logging {

  def getUrl(uriImage: UriImage): String = {
    s3ImageConfig.cdnBase + "/" + uriImage.imagePath
  }

  def processRemoteImage(remoteImageUrl: String): Future[Either[Seq[ImageProcessState.UploadedImage], ImageStoreFailure]] = {
    fetchAndHashRemoteImage(remoteImageUrl).flatMap {
      case Right(originalImage) =>
        buildPersistSet(originalImage, "i")(photoshop) match {
          case Right(toPersist) =>
            val uploads = toPersist.map { image =>
              log.info(s"[uic] Persisting ${image.key} (${image.bytes} B)")
              uriImageStore.put(image.key, image.is, image.bytes, imageFormatToMimeType(image.format)).map { r =>
                ImageProcessState.UploadedImage(image.key, image.format, image.image)
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
