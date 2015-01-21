
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

  // Fetch, resize, persist to S3
  //def processRemoteImage(): Future[Either[Seq[UriImage], ImageStoreFailure]]

  def getUrl(libraryImage: UriImage): String
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

  //
  // Internal helper methods!
  //

  private def fetchAndSet(remoteImageUrl: String, libraryId: Id[Library], source: ImageSource): Future[ImageProcessDone] = {
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
              val uploadedOriginal = results.find { uploadedImage =>
                uploadedImage.key.takeRight(7).indexOf(originalLabel) != -1
              }.getOrElse(results.head)

              ImageProcessState.StoreSuccess(originalImage.format, ImageSize(uploadedOriginal.image.getWidth, uploadedOriginal.image.getHeight), originalImage.file.file.length.toInt)
            }.recover {
              case ex: Throwable =>
                ImageProcessState.CDNUploadFailed(ex)
            }
          case Left(failure) =>
            Future.successful(failure)
        }
      case Left(failure) => Future.successful(failure)
    }
  }

}
