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
import com.keepit.model.{ ImageInfo, NormalizedURI, URISummary }
import com.keepit.scraper.ShoeboxDbCallbackHelper
import com.keepit.scraper.embedly.EmbedlyClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.net.URI

@ImplementedBy(classOf[ScraperURISummaryCommanderImpl])
trait ScraperURISummaryCommander {
  def fetchFromEmbedly(uri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]]
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

  private def fetchAndInternImage(uri: NormalizedURI, imageInfo: ImageInfo): Future[Option[ImageInfo]] = {
    imageInfo.url match {
      case Some(imageUrl) => imageFetcher.fetchRawImage(URI.parse(imageUrl).get).map { rawImageOpt =>
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
      case Success(result) =>
        val (url, size) = result
        val imageInfoWithUrl = if (info.url.isEmpty) info.copy(url = Some(url), size = Some(size)) else info
        callback.syncSaveImageInfo(imageInfoWithUrl)
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

  override def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]] = {
    val watch = Stopwatch(s"[embedly] asking for $nUri with minSize $minSize, descriptionOnly $descriptionOnly")
    val fullEmbedlyInfo = embedlyClient.getEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>
      watch.logTimeWith(s"[embedly] for uri $nUri got info: $embedlyInfoOpt") //this could be lots of logging, should remove it after problems resolved

      val summary = for {
        nUriId <- nUri.id
        embedlyInfo <- embedlyInfoOpt
      } yield {

        //here we call back shoebox, in the middle of shoebox calling us, right?
        timing(s"[embedly] syncSavePageInfo for uri ${nUriId.id}") {
          callback.syncSavePageInfo(embedlyInfo.toPageInfo(nUriId))
        }

        if (descriptionOnly) {
          Future.successful(Some(URISummary(None, embedlyInfo.title, embedlyInfo.description)))
        } else {
          val images = embedlyInfo.buildImageInfo(nUriId)
          val nonBlankImages = images.filter { image =>
            image.url map ScraperURISummaryCommander.filterImageByUrl getOrElse false
          }
          val (smallImages, selectedImageOpt) = partitionImages(nonBlankImages, minSize)

          timing(s"[embedly] fetching ${smallImages.size} small images of ${nUriId.id}") {
            //Why are we fetching all those small images? Why do we do in in sync?
            smallImages.foreach { fetchAndInternImage(nUri, _) }
          }

          selectedImageOpt match {
            case None =>
              watch.logTimeWith(s"[embedly] no selected image for ${nUriId.id}")
              Future.successful(Some(URISummary(None, embedlyInfo.title, embedlyInfo.description)))
            case Some(image) =>
              watch.logTimeWith(s"[embedly] got a selected image for ${nUriId.id} : $image")
              val future = fetchAndInternImage(nUri, image) map { imageInfoOpt =>
                val urlOpt = imageInfoOpt.flatMap(getS3URL(_, nUri))
                val widthOpt = imageInfoOpt.flatMap(_.width)
                val heightOpt = imageInfoOpt.flatMap(_.height)
                Some(URISummary(urlOpt, embedlyInfo.title, embedlyInfo.description, widthOpt, heightOpt))
              }
              future.onComplete { res =>
                watch.logTimeWith(s"[embedly] [success = ${res.isSuccess}}] fetched a selected image for ${nUriId.id} : $image")
              }
              future
          }
        }
      }
      summary getOrElse Future.successful(None)
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
