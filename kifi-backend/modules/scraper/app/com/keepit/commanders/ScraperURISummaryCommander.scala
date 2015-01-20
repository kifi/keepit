package com.keepit.commanders

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.performance._

import java.awt.image.BufferedImage

import com.keepit.common.logging.Logging
import com.kifi.macros.json
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.{ Failure, Success }

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.common.store.{ ImageSize, S3URIImageStore }
import com.keepit.model.{ PageAuthor, PageInfo, ImageInfo, URISummary }
import com.keepit.scraper.{ URIPreviewFetchResult, NormalizedURIRef, ShoeboxDbCallbackHelper }
import com.keepit.scraper.embedly.{ EmbedlyImage, EmbedlyClient }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.net.URI

@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  // On the way in:
  def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]]
  // On the way out:
  def fetchFromEmbedly(uri: NormalizedURIRef, descriptionOnly: Boolean): Future[Option[URISummary]]
}

class ScraperURISummaryCommanderImpl @Inject() (
    imageFetcher: ImageFetcher,
    embedlyClient: EmbedlyClient,
    uriImageStore: S3URIImageStore,
    airbrake: AirbrakeNotifier,
    callback: ShoeboxDbCallbackHelper) extends ScraperURISummaryCommander with Logging {

  def fetchFromEmbedly(nUri: NormalizedURIRef, descriptionOnly: Boolean): Future[Option[URISummary]] = {
    fetchPageInfoAndImageInfo(nUri, descriptionOnly) map {
      case (Some(pageInfo), imageInfoOpt) =>
        callback.savePageInfo(pageInfo) // no wait

        imageInfoOpt match {
          case Some(imageInfo) =>
            callback.saveImageInfo(imageInfo) // no wait

            val urlOpt = imageInfoOpt.flatMap(getS3URL(_, nUri))
            val widthOpt = imageInfoOpt.flatMap(_.width)
            val heightOpt = imageInfoOpt.flatMap(_.height)
            Some(URISummary(urlOpt, pageInfo.title, pageInfo.description, widthOpt, heightOpt))
          case None =>
            Some(URISummary(None, pageInfo.title, pageInfo.description))
        }
      case _ => None
    }
  }

  def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]] = {
    embedlyClient.getEmbedlyInfo(url).map {
      case Some(embedlyResult) =>
        val primaryImage = embedlyResult.images.find(img => ScraperURISummaryCommander.isValidImage(img))
        // todo: Resize and persist

        Some(URIPreviewFetchResult(
          pageUrl = url,
          title = embedlyResult.title,
          description = embedlyResult.description,
          authors = embedlyResult.authors,
          publishedAt = embedlyResult.published,
          safe = embedlyResult.safe,
          lang = embedlyResult.lang,
          faviconUrl = embedlyResult.faviconUrl.collect { case f if f.startsWith("http") => f },
          images = None // todo
        ))

      case None => None
    }
  }

  // Internal:

  private def fetchPageInfoAndImageInfo(nUri: NormalizedURIRef, descriptionOnly: Boolean): Future[(Option[PageInfo], Option[ImageInfo])] = {
    val watch = Stopwatch(s"[embedly] asking for $nUri, descriptionOnly $descriptionOnly")
    val fullEmbedlyInfo = embedlyClient.getEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>
      watch.logTimeWith(s"got info: $embedlyInfoOpt") //this could be lots of logging, should remove it after problems resolved

      val summaryOptF = for {
        embedlyInfo <- embedlyInfoOpt
      } yield {
        val imageInfoOptF: Future[Option[ImageInfo]] = {
          if (descriptionOnly) {
            Future.successful(None)
          } else {
            val images = embedlyInfo.buildImageInfo(nUri.id)
            val nonBlankImages = images.filter { image => image.url.exists(ScraperURISummaryCommander.isValidImageUrl) }

            // todo: Upload nonBlankImages to S3.

            nonBlankImages.headOption match {
              case None =>
                watch.logTimeWith(s"no selected image")
                Future.successful(None)
              case Some(image) =>
                watch.logTimeWith(s"got a selected image : $image")
                val future = fetchAndSaveImage(nUri, image)
                future.onComplete { res =>
                  watch.logTimeWith(s"[success = ${res.isSuccess}}] fetched a selected image for : $image")
                }
                future
            }
          }
        }
        imageInfoOptF map { imageInfoOpt => (Some(embedlyInfo.toPageInfo(nUri.id)), imageInfoOpt) }
      }
      summaryOptF getOrElse Future.successful((None, None))
    }
    fullEmbedlyInfo.onComplete { res =>
      watch.stop()
    }
    fullEmbedlyInfo
  }

  private def fetchAndSaveImage(uri: NormalizedURIRef, imageInfo: ImageInfo): Future[Option[ImageInfo]] = {
    imageInfo.url match {
      case Some(imageUrl) => imageFetcher.fetchRawImage(URI.parse(imageUrl).get) map { rawImageOpt =>
        rawImageOpt flatMap { rawImage => storeImage(imageInfo, rawImage, uri) }
      }
      case None => Future.successful(None)
    }
  }

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

  /**
   * Get S3 url for image info
   */
  private def getS3URL(info: ImageInfo, nUri: NormalizedURIRef): Option[String] = uriImageStore.getImageURL(info, nUri.externalId)

  private def meetsSizeConstraint(info: ImageInfo, size: ImageSize): Boolean = {
    for {
      width <- info.width
      height <- info.height
    } yield {
      return (width > size.width && height > size.height)
    }
    false
  }

  private def fetchSmallImage(uri: NormalizedURIRef, imageInfo: ImageInfo): Unit = {
    fetchAndSaveImage(uri, imageInfo) map { imageInfoOpt =>
      imageInfoOpt foreach { info => callback.saveImageInfo(info) }
    }
  }

}

object ScraperURISummaryCommander {

  val IMAGE_EXCLUSION_LIST = Seq("/blank.jpg", "/blank.png", "/blank.gif")

  def isValidImage(embedlyImage: EmbedlyImage): Boolean = {
    isValidImageUrl(embedlyImage.url)
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
