package com.keepit.commanders

import java.awt.image.BufferedImage

import com.keepit.common.logging.Logging
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.{ Failure, Success }

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.common.store.{ S3ImageConfig, ImageSize, S3URIImageStore }
import com.keepit.model._
import com.keepit.scraper.{ URIPreviewFetchResult, NormalizedURIRef, ShoeboxDbCallbackHelper }
import com.keepit.scraper.embedly.{ EmbedlyImage, EmbedlyClient }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.net.URI
import com.keepit.common.performance._
import com.keepit.common.store.S3URIImageStore
import com.keepit.model.{ ImageStoreFailureWithException, ImageInfo, PageInfo, URISummary }
import com.keepit.scraper.embedly.{ EmbedlyClient, EmbedlyImage }
import com.keepit.scraper._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  // On the way in:
  def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]]
  // On the way out:
  def fetchFromEmbedly(uri: NormalizedURIRef): Future[Option[URISummary]]
}

class ScraperURISummaryCommanderImpl @Inject() (
    imageFetcher: ImageFetcher,
    embedlyClient: EmbedlyClient,
    s3URIImageStore: S3URIImageStore,
    uriImageStore: S3URIImageStore,
    airbrake: AirbrakeNotifier,
    uriImageCommander: UriImageCommander,
    callback: ShoeboxDbCallbackHelper) extends ScraperURISummaryCommander with Logging {

  def fetchFromEmbedly(nUri: NormalizedURIRef): Future[Option[URISummary]] = {
    fetchPageInfoAndImageInfo(nUri) map {
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
    embedlyClient.getEmbedlyInfo(url).flatMap {
      case Some(embedlyResult) =>
        val primaryImage = embedlyResult.images.find(img => ScraperURISummaryCommander.isValidImage(img))

        val imagesF = primaryImage match {
          case Some(embedlyImage) =>
            log.info(s"[susc] Got ${embedlyImage.url} for $url, fetching, resizing, and persisting.")
            uriImageCommander.processRemoteImage(embedlyImage.url).map {
              case Left(uploadResults) =>
                val sizes = uploadResults.map { upload =>
                  PersistedImageVersion(upload.image.getWidth, upload.image.getHeight, upload.key)
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

  private def fetchPageInfoAndImageInfo(nUri: NormalizedURIRef): Future[(Option[PageInfo], Option[ImageInfo])] = {
    val watch = Stopwatch(s"[embedly] asking for $nUri")
    val fullEmbedlyInfo = embedlyClient.getEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>
      watch.logTimeWith(s"got info: $embedlyInfoOpt") //this could be lots of logging, should remove it after problems resolved

      val summaryOptF = for {
        embedlyInfo <- embedlyInfoOpt
      } yield {
        val imageInfoOptF: Future[Option[ImageInfo]] = {
          val name = RandomStringUtils.randomAlphanumeric(5)
          val path = s3URIImageStore.getEmbedlyImageKey(nUri.externalId, name, ImageFormat.JPG.value)
          val images = embedlyInfo.buildImageInfo(nUri.id, path, name)
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
