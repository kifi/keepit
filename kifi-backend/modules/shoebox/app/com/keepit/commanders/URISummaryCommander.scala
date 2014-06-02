package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import scala.concurrent._
import play.api.libs.json.{Json, JsValue}
import com.keepit.model._
import scala.Some
import com.keepit.common.store.{S3URIImageStore, ImageSize}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.slick.Database
import com.keepit.common.pagepeeker.PagePeekerClient
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import scala.util.{Success, Failure}
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.collection.mutable
import com.keepit.common.time._
import com.keepit.scraper.ScraperServiceClient
import com.keepit.scraper.embedly.EmbedlyStore
import com.keepit.common.db.Id

class URISummaryCommander @Inject()(
  normalizedUriRepo: NormalizedURIRepo,
  imageInfoRepo: ImageInfoRepo,
  pageInfoRepo: PageInfoRepo,
  db: Database,
  scraper: ScraperServiceClient,
  pagePeekerClient: PagePeekerClient,
  uriImageStore: S3URIImageStore,
  embedlyStore: EmbedlyStore,
  imageFetcher: ImageFetcher,
  airbrake: AirbrakeNotifier,
  clock: Clock
) extends Logging {

  /**
   * Gets an image for the given URI. It can be an image on the page or a screenshot, and there are no size restrictions
   * If no image is available, fetching is triggered (silent=false) but the promise is immediately resolved (waiting=false)
   */
  def getURIImage(nUri: NormalizedURI): Future[Option[String]] = {
    val request = URISummaryRequest(nUri.url, ImageType.ANY, ImageSize(0,0), false, false, false)
    getURISummaryForRequest(request, nUri) map { _.imageUrl }
  }

  /**
   * URI summaries are "best effort", which means partial results can be returned (for example if a description is required
   * and only the image is found, the image should still be returned
   */
  def getURISummaryForRequest(request: URISummaryRequest): Future[URISummary] = {
    getNormalizedURIForRequest(request) map { nUri =>
      getURISummaryForRequest(request, nUri)
    } getOrElse Future.successful(URISummary())
  }

  private def getURISummaryForRequest(request: URISummaryRequest, nUri: NormalizedURI): Future[URISummary] = {
    val summary = getStoredSummaryForRequest(nUri, request.imageType, request.minSize, request.withDescription)
    if (!isCompleteSummary(summary, request)) {
      if (!request.silent) {
        val fetchedSummary = fetchSummaryForRequest(nUri, request.imageType, request.minSize, request.withDescription)
        if (request.waiting) fetchedSummary else Future.successful(summary)
      } else Future.successful(summary)
    } else Future.successful(summary)
  }

  private def getNormalizedURIForRequest(request: URISummaryRequest): Option[NormalizedURI] = {
    if (request.silent)
      db.readOnly { implicit session => normalizedUriRepo.getByUri(request.url) }
    else
      db.readWrite { implicit session => Some(normalizedUriRepo.internByUri(request.url)) }
  }

  /**
   * Check if the URI summary contains everything the request needs. As of now, all requests require an image/screenshot
   */
  private def isCompleteSummary(summary: URISummary, request: URISummaryRequest): Boolean = {
    !summary.imageUrl.isEmpty && (summary.description.nonEmpty || !request.withDescription)
  }

  /**
   * Retrieves URI summary data by only looking at the database
   */
  private def getStoredSummaryForRequest(nUri: NormalizedURI, imageType: ImageType, minSize: ImageSize, withDescription: Boolean): URISummary = {
    val targetProvider = imageType match {
      case ImageType.ANY => None
      case ImageType.SCREENSHOT => Some(ImageProvider.PAGEPEEKER)
      case ImageType.IMAGE => Some(ImageProvider.EMBEDLY)
    }
    val storedImagesOpt = nUri.id map { id =>
      db.readOnly { implicit session => imageInfoRepo.getByUriWithSize(id, minSize) }
    }
    val storedSummaryOpt = storedImagesOpt flatMap { list =>
      val candidates = list.filter(targetProvider.isEmpty || targetProvider == _.provider)
      if (candidates.nonEmpty) {
        val imageInfo = candidates.minBy(_.priority.getOrElse(Int.MaxValue))
        if (withDescription) {
          for {
            nUriId <- nUri.id
            pageInfo <- db.readOnly { implicit session => pageInfoRepo.getByUri(nUriId) }
          } yield {
            URISummary(getS3URL(imageInfo, nUri), pageInfo.title, pageInfo.description)
          }
        }
        else {
          Some(URISummary(getS3URL(imageInfo, nUri)))
        }
      } else None
    }
    storedSummaryOpt getOrElse URISummary()
  }

  /**
   * Retrieves URI summary data from external services (Embedly, PagePeeker)
   */
  private def fetchSummaryForRequest(nUri: NormalizedURI, imageType: ImageType, minSize: ImageSize, withDescription: Boolean): Future[URISummary] = {
    val embedlyResultFut = if (imageType == ImageType.IMAGE || imageType == ImageType.ANY || withDescription) fetchFromEmbedly(nUri, minSize)
    else Future.successful(None)
    embedlyResultFut flatMap { embedlyResultOpt =>
      val shouldFetchFromPagePeeker =
        (imageType == ImageType.SCREENSHOT || imageType == ImageType.ANY) &&  // Request accepts screenshots
        (embedlyResultOpt.isEmpty || embedlyResultOpt.get.imageUrl.isEmpty)   // Couldn't find appropriate Embedly image
      if (shouldFetchFromPagePeeker) {
        fetchFromPagePeeker(nUri, minSize) map { imageInfoOpt =>
          val imageUrlOpt = imageInfoOpt flatMap { getS3URL(_, nUri) }
          val title = embedlyResultOpt flatMap { _.title }
          val description = embedlyResultOpt flatMap { _.description }
          URISummary(imageUrlOpt, title, description)
        }
      } else Future.successful(embedlyResultOpt getOrElse URISummary())
    }
  }

  /**
   * Triggers screenshot update and returns resulting image info
   */
  def updateScreenshots(nUri: NormalizedURI): Future[Option[ImageInfo]] = fetchFromPagePeeker(nUri, ImageSize(0,0))

  /**
   * The default size screenshot URL is returned (when the screenshot exists).
   */
  def getScreenshotURL(nUri: NormalizedURI, silent: Boolean = false): Option[String] = {
    if (nUri.screenshotUpdatedAt.isEmpty) {
      if (!silent) updateScreenshots(nUri)
      None
    }
    else uriImageStore.getDefaultScreenshotURL(nUri)
  }


  /**
   * Fetches images and/or page description from Embedly. The retrieved information is persisted to the database
   */
  private def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize = ImageSize(0, 0), descriptionOnly: Boolean = false): Future[Option[URISummary]] = {
    scraper.getURISummaryFromEmbedly(nUri, minSize, descriptionOnly)
  }

  /**
   * Fetches screenshot from PagePeeker. All generated screenshots are persisted to the database
   */
  private def fetchFromPagePeeker(nUri: NormalizedURI, minSize: ImageSize): Future[Option[ImageInfo]] = {
    nUri.id map { nUriId =>
      pagePeekerClient.getScreenshotData(nUri) map { imagesOpt =>
        imagesOpt flatMap { images =>
          val candidates = images map { image =>
            val imageInfo = image.toImageInfo(nUriId)
            internImage(imageInfo, image.rawImage, nUri)
          }
          val successfulCandidates = candidates collect { case Some(candidate) => candidate }
          if (successfulCandidates.length == candidates.length) {
            db.readWrite { implicit session => normalizedUriRepo.updateScreenshotUpdatedAt(nUriId, clock.now) }
          } else {
            log.error(s"Failed to update screenshots for normalized URI $nUriId}")
          }
          successfulCandidates find { meetsSizeConstraint(_, minSize) }
        }
      }
    } getOrElse Future.successful(None)
  }

  /**
   * Stores image to S3
   */
  private def internImage(info: ImageInfo, image: BufferedImage, nUri: NormalizedURI): Option[ImageInfo] = {
    uriImageStore.storeImage(info, image, nUri) match {
      case Success(result) => {
        val (url, size) = result
        val imageInfoWithUrl = if (info.url.isEmpty) info.copy(url = Some(url), size = Some(size)) else info
        db.readWrite { implicit session => imageInfoRepo.save(imageInfoWithUrl) }
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

  def getStoredEmbedlyKeywords(id: Id[NormalizedURI]): Seq[String] = {
    embedlyStore.get(id) match {
      case Some(info) => info.info.keywords.sortBy(-1 * _.score).map{_.name}
      case None => Seq()
    }
  }

  //todo(martin) method to prune obsolete images from S3 (i.e. remove image if there is a newer image with at least the same size and priority)
}
