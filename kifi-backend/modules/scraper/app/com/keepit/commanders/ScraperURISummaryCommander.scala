package com.keepit.commanders

import com.keepit.common.performance._

import java.awt.image.BufferedImage

import com.keepit.common.logging.Logging

import scala.concurrent.Future
import scala.util.{ Failure, Success }

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.ImageFetcher
import com.keepit.common.store.{ ImageSize, S3URIImageStore }
import com.keepit.model.{ PageInfo, ImageInfo, NormalizedURI, URISummary }
import com.keepit.scraper.ShoeboxDbCallbackHelper
import com.keepit.scraper.embedly.EmbedlyClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.net.URI

@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  def fetchFromEmbedly(uri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]]
  def fetchPageInfoAndImageInfo(nUri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[(Option[PageInfo], Option[ImageInfo])]
}

class ScraperURISummaryCommanderImpl @Inject() (
    imageFetcher: ImageFetcher,
    embedlyClient: EmbedlyClient,
    uriImageStore: S3URIImageStore,
    airbrake: AirbrakeNotifier,
    callback: ShoeboxDbCallbackHelper) extends ScraperURISummaryCommander with Logging {

  private def partitionImages(imgsInfo: Seq[ImageInfo], minSize: ImageSize): (Seq[ImageInfo], Option[ImageInfo]) = {
    val smallImages = imgsInfo.takeWhile(!meetsSizeConstraint(_, minSize))
    (smallImages, imgsInfo.drop(smallImages.size).headOption)
  }

  private def fetchAndSaveImage(uri: NormalizedURI, imageInfo: ImageInfo): Future[Option[ImageInfo]] = {
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
  private def storeImage(info: ImageInfo, image: BufferedImage, nUri: NormalizedURI): Option[ImageInfo] = {
    uriImageStore.storeImage(info, image, nUri) match {
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

  private def fetchSmallImage(uri: NormalizedURI, imageInfo: ImageInfo): Unit = {
    fetchAndSaveImage(uri, imageInfo) map { imageInfoOpt =>
      imageInfoOpt foreach { info => callback.saveImageInfo(info) }
    }
  }

  override def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]] = {
    fetchPageInfoAndImageInfo(nUri, minSize, descriptionOnly) map {
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

  override def fetchPageInfoAndImageInfo(nUri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[(Option[PageInfo], Option[ImageInfo])] = {
    val watch = Stopwatch(s"[embedly] asking for $nUri with minSize $minSize, descriptionOnly $descriptionOnly")
    val fullEmbedlyInfo = embedlyClient.getEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>
      watch.logTimeWith(s"got info: $embedlyInfoOpt") //this could be lots of logging, should remove it after problems resolved

      val summaryOptF = for {
        nUriId <- nUri.id
        embedlyInfo <- embedlyInfoOpt
      } yield {
        val imageInfoOptF: Future[Option[ImageInfo]] = {
          if (descriptionOnly) {
            Future.successful(None)
          } else {
            val images = embedlyInfo.buildImageInfo(nUriId)
            val nonBlankImages = images.filter { image => image.url.exists(ScraperURISummaryCommander.filterImageByUrl) }
            val (smallImages, selectedImageOpt) = partitionImages(nonBlankImages, minSize)

            timing(s"fetching ${smallImages.size} small images") {
              smallImages.foreach {
                fetchSmallImage(nUri, _)
              }
            }
            watch.logTimeWith(s"all ${smallImages.size} small images")

            selectedImageOpt match {
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
        imageInfoOptF map { imageInfoOpt => (Some(embedlyInfo.toPageInfo(nUriId)), imageInfoOpt) }
      }
      summaryOptF getOrElse Future.successful((None, None))
    }
    fullEmbedlyInfo.onComplete { res =>
      watch.stop()
    }
    fullEmbedlyInfo
  }

}

object ScraperURISummaryCommander {

  val IMAGE_EXCLUSION_LIST = Seq("/blank.jpg", "/blank.png", "/blank.gif")

  def filterImageByUrl(url: String): Boolean = {
    URI.parse(url) match {
      case Success(imageUri) => {
        imageUri.path.map { path =>
          !IMAGE_EXCLUSION_LIST.exists(path.toLowerCase.endsWith(_))
        } getOrElse true
      }
      case Failure(imageUrl) => true
    }
  }
}
