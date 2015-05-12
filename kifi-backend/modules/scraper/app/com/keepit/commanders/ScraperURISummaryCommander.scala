package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.rover.article.content.EmbedlyImage
import com.keepit.shoebox.ShoeboxScraperClient

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.scraper.{ URIPreviewFetchResult }

import com.keepit.common.net.URI
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
                val message = s"[susc] Couldn't fetch/persist image from $url. Reason: ${failure.reason}"
                failure.cause.map(log.error(message, _)) getOrElse log.error(message)
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
