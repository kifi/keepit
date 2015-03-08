package com.keepit.commanders

import java.awt.image.BufferedImage

import com.keepit.common.logging.Logging
import com.keepit.rover.article.EmbedlyImage
import com.keepit.shoebox.ShoeboxScraperClient

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.scraper.{ URIPreviewFetchResult, NormalizedURIRef }

import com.keepit.common.net.URI
import com.keepit.common.store.{ S3ImageConfig, S3URIImageStore }
import com.keepit.model.{ ImageStoreFailureWithException, ImageInfo }
import com.keepit.scraper.embedly.EmbedlyClient
import com.keepit.scraper._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  // On the way in:
  def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]]
}

class ScraperURISummaryCommanderImpl @Inject() (
    imageFetcher: ImageFetcher,
    embedlyClient: EmbedlyClient,
    s3URIImageStore: S3URIImageStore,
    uriImageStore: S3URIImageStore,
    imageConfig: S3ImageConfig,
    airbrake: AirbrakeNotifier,
    uriImageCommander: UriImageCommander,
    shoeboxScraperClient: ShoeboxScraperClient) extends ScraperURISummaryCommander with Logging {

  def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]] = {
    embedlyClient.getEmbedlyInfo(url).flatMap {
      case Some(embedlyResult) =>
        val primaryImage = embedlyResult.images.find(img => ScraperURISummaryCommander.isValidImage(img))

        val imagesF = primaryImage match {
          case Some(embedlyImage) =>
            log.info(s"[susc] Got ${embedlyImage.url} for $url, fetching, resizing, and persisting.")
            uriImageCommander.processRemoteImage(embedlyImage.url).map {
              case Left(uploadResults) =>
                val sizes = uploadResults.map { upload =>
                  PersistedImageVersion(upload.image.getWidth, upload.image.getHeight, upload.key, embedlyImage.url)
                }
                log.info(s"[susc] Done, uploaded ${uploadResults.length} images: ${sizes}")
                Some(PersistedImageRef(sizes, embedlyImage.caption))
              case Right(failure) =>
                failure match {
                  case f: ImageStoreFailureWithException =>
                    log.error(s"[susc] Failure fetching/persisting image from $url. Reason: ${f.reason}", f.getCause)
                  case f =>
                    log.error(s"[susc] Couldn't fetch/persist image from $url. Reason: ${f.reason}")
                }
                None
            }
          case None => // embedly didn't have any images
            Future.successful(None)
        }
        imagesF.map { images =>
          Option(URIPreviewFetchResult(
            pageUrl = url,
            title = embedlyResult.title,
            description = embedlyResult.description,
            authors = embedlyResult.authors,
            publishedAt = embedlyResult.published,
            safe = embedlyResult.safe,
            lang = embedlyResult.lang,
            faviconUrl = embedlyResult.faviconUrl.collect { case f if f.startsWith("http") => f },
            images = images
          ))
        }

      case None =>
        Future.successful(None)
    }
  }

  // Internal:

  /**
   * Stores image to S3
   */
  private def storeImage(info: ImageInfo, image: BufferedImage, nUri: NormalizedURIRef): Option[ImageInfo] = {
    uriImageStore.storeImage(info, image, nUri.externalId) match {
      case Success(result) =>
        val (url, size) = result
        val imageInfoWithUrl = if (info.url.isEmpty) info.copy(url = Some(url), size = Some(size)) else info
        Some(imageInfoWithUrl)
      case Failure(ex) =>
        airbrake.notify(s"Failed to upload URL image to S3: ${ex.getMessage}")
        None
    }
  }

}

object ScraperURISummaryCommander {

  val IMAGE_EXCLUSION_LIST = Seq("/blank.jpg", "/blank.png", "/blank.gif")

  def isValidImage(embedlyImage: EmbedlyImage): Boolean = {
    // If embedly height and width aren't defined, may just be because they haven't fetched the image yet.
    val bigEnough = embedlyImage.height.map(_ > 60).getOrElse(true) && embedlyImage.width.map(_ > 60).getOrElse(true)
    bigEnough && isValidImageUrl(embedlyImage.url)
  }

  def isValidImageUrl(url: String): Boolean = {
    URI.parse(url) match {
      case Success(imageUri) => {
        imageUri.path.exists { path =>
          !IMAGE_EXCLUSION_LIST.exists(path.toLowerCase.endsWith)
        }
      }
      case Failure(imageUrl) => false
    }
  }
}
