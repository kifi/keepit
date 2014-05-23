package com.keepit.commanders

import java.awt.image.BufferedImage

import scala.concurrent.Future
import scala.util.{Failure, Success}

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.common.store.{ImageSize, S3URIImageStore}
import com.keepit.model.{ImageInfo, NormalizedURI, URISummary}
import com.keepit.scraper.ShoeboxDbCallbackHelper
import com.keepit.scraper.embedly.EmbedlyClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext


@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  def fetchFromEmbedly(uri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]]
}

class ScraperURISummaryCommanderImpl @Inject()(
  imageFetcher: ImageFetcher,
  embedlyClient: EmbedlyClient,
  uriImageStore: S3URIImageStore,
  airbrake: AirbrakeNotifier,
  callback: ShoeboxDbCallbackHelper
) extends ScraperURISummaryCommander {

  private def partitionImages(imgsInfo: Seq[ImageInfo], minSize: ImageSize): (Seq[ImageInfo], Option[ImageInfo]) = {
    val smallImages = imgsInfo.takeWhile(!meetsSizeConstraint(_, minSize))
    (smallImages, imgsInfo.drop(smallImages.size).headOption)
  }

  private def fetchAndInternImage(uri: NormalizedURI, imageInfo: ImageInfo): Future[Option[ImageInfo]] = {
    imageInfo.url match {
      case Some(imageUrl) => imageFetcher.fetchRawImage(imageUrl).map{ rawImageOpt =>
        rawImageOpt flatMap { rawImage => internImage(imageInfo, rawImage, uri) }
      }
      case None => Future.successful(None)
    }
  }

  /**
   * Stores image to S3
   */
  private def internImage(info: ImageInfo, image: BufferedImage, nUri: NormalizedURI): Option[ImageInfo] = {
    uriImageStore.storeImage(info, image, nUri) match {
      case Success(result) => {
        val (url, size) = result
        val imageInfoWithUrl = if (info.url.isEmpty) info.copy(url = Some(url), size = Some(size)) else info

        callback.saveImageInfo(imageInfoWithUrl)
        Some(imageInfoWithUrl)
      }
      case Failure(ex) => {
        airbrake.notify(s"Failed to upload URL image to S3: ${ex.getMessage()}")
        None
      }
    }
  }

  /**
   * Get S3 url for image info
   */
  private def getS3URL(info: ImageInfo, nUri: NormalizedURI): Option[String] = uriImageStore.getImageURL(info, nUri)

  private def meetsSizeConstraint(info: ImageInfo, size: ImageSize): Boolean = {
    for {
      width <- info.width
      height <- info.height
    } yield {
      return (width > size.width && height > size.height)
    }
    false
  }

  override def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]] = {
      embedlyClient.getExtendedEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>

      val summary = for {
        nUriId <- nUri.id
        embedlyInfo <- embedlyInfoOpt
      } yield {

        callback.savePageInfo(embedlyInfo.toPageInfo(nUriId))

        if (descriptionOnly) {
          Future.successful(Some(URISummary(None, embedlyInfo.title, embedlyInfo.description)))
        } else {
          val images = embedlyInfo.buildImageInfo(nUriId)
          val (smallImages, selectedImageOpt) = partitionImages(images, minSize)

          smallImages.foreach { fetchAndInternImage(nUri, _) }

          selectedImageOpt match {
            case None => Future.successful(Some(URISummary(None, embedlyInfo.title, embedlyInfo.description)))
            case Some(image) =>
              fetchAndInternImage(nUri, image) map { imageInfoOpt =>
                Some(URISummary(imageInfoOpt flatMap { getS3URL(_, nUri) }, embedlyInfo.title, embedlyInfo.description))
              }
          }
        }
      }

      summary getOrElse Future.successful(None)

    }
  }

}