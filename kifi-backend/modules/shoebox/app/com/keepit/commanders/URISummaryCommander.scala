package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import com.keepit.controllers.RequestSource
import scala.concurrent._
import play.api.libs.json.{Json, JsValue}
import com.keepit.model._
import scala.Some
import com.keepit.common.store.{S3URIImageStore, ImageSize}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.slick.Database
import com.keepit.common.embedly.EmbedlyClient
import com.keepit.common.pagepeeker.PagePeekerClient
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import scala.util.{Success, Failure}
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.collection.mutable
import com.keepit.common.time._

case class URISummaryRequest(
  url: String,
  imageType: ImageType,
  minSize: ImageSize,
  requestSource: RequestSource,
  withDescription: Boolean,
  waiting: Boolean,
  silent: Boolean)

case class URISummary(imageUrl: Option[String] = None, description: Option[String] = None)

class URISummaryCommander @Inject()(
  normalizedUriRepo: NormalizedURIRepo,
  imageInfoRepo: ImageInfoRepo,
  pageInfoRepo: PageInfoRepo,
  db: Database,
  embedlyClient: EmbedlyClient,
  pagePeekerClient: PagePeekerClient,
  uriImageStore: S3URIImageStore,
  imageFetcher: ImageFetcher,
  airbrake: AirbrakeNotifier,
  clock: Clock
) extends Logging {

  /**
   * Handles JSON-formatted URI summary requests
   */
  def getURISummary(value: JsValue, source: RequestSource): Future[JsValue] = {
    parseRequest(value, source) match {
      case Left(request) => {
        getNormalizedURIForRequest(request).map { nUri =>
          getURISummaryForRequest(request, nUri) map { uriSummary =>
            val results = new mutable.HashMap[String, String]()
            uriSummary.imageUrl map { imageUrl =>
              results += ("url" -> imageUrl)
            }
            uriSummary.description foreach { description => results += ("description" -> description) }
            if (!isCompleteSummary(uriSummary, request)) results += ("error" -> "incomplete_data")
            Json.toJson(results.toMap)
          }
        } getOrElse future{Json.obj("error" -> "no_data_found")}
      }
      case Right(error) => future {Json.obj("error" -> error)}
    }
  }

  private def parseRequest(value: JsValue, source: RequestSource): Either[URISummaryRequest,String] = {
    (value \ "url").asOpt[String] map { url =>
      (value \ "width").asOpt[Int] map { width =>
        (value \ "height").asOpt[Int] map { height =>
          val imageType = (value \ "type").asOpt[ImageType].getOrElse(ImageType.ANY)
          val withDescription = (value \ "with_description").asOpt[Boolean].getOrElse(true)
          val waiting = (value \ "waiting").asOpt[Boolean].getOrElse(true)
          val silent = (value \ "silent").asOpt[Boolean].getOrElse(false)
          Left(URISummaryRequest(url, imageType, ImageSize(width, height), RequestSource.EXTENSION, withDescription, waiting, silent))
        } getOrElse Right("missing_min_height")
      } getOrElse Right("missing_min_width")
    } getOrElse Right("missing_url")
  }

  /**
   * Gets an image for the given URI. It can be an image on the page or a screenshot, and there are no size restrictions
   * Fetching occurs if no image exists in the database
   */
  def getURIImage(nUri: NormalizedURI, requestSource: RequestSource = RequestSource.UNKNOWN): Future[Option[String]] = {
    val request = URISummaryRequest(nUri.url, ImageType.ANY, ImageSize(0,0), requestSource, false, true, false)
    getURISummaryForRequest(request, nUri) map { _.imageUrl }
  }

  /**
   * URI summaries are "best effort", which means partial results can be returned (for example if a description is required
   * and only the image is found, the image should still be returned
   */
  def getURISummaryForRequest(request: URISummaryRequest): Future[URISummary] = {
    getNormalizedURIForRequest(request) map { nUri =>
      getURISummaryForRequest(request, nUri)
    } getOrElse (future{URISummary()})
  }

  private def getURISummaryForRequest(request: URISummaryRequest, nUri: NormalizedURI): Future[URISummary] = {
    val summary = getStoredSummaryForRequest(nUri, request.imageType, request.minSize, request.withDescription)
    if (!isCompleteSummary(summary, request)) {
      if (!request.silent) {
        val fetchedSummary = fetchSummaryForRequest(nUri, request.imageType, request.minSize, request.withDescription)
        if (request.waiting) fetchedSummary else future{summary}
      } else future{summary}
    } else future{summary}
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
            description <- pageInfo.description
          } yield {
            URISummary(getS3URL(imageInfo, nUri), Some(description))
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
    else future{None}
    embedlyResultFut flatMap { embedlyResultOpt =>
      val shouldFetchFromPagePeeker =
        (imageType == ImageType.SCREENSHOT || imageType == ImageType.ANY) &&  // Request accepts screenshots
        (embedlyResultOpt.isEmpty || embedlyResultOpt.get.imageUrl.isEmpty)   // Couldn't find appropriate Embedly image
      val description = embedlyResultOpt flatMap { _.description }
      if (shouldFetchFromPagePeeker) {
        fetchFromPagePeeker(nUri, minSize) map { imageInfoOpt =>
          val imageUrlOpt = imageInfoOpt flatMap { getS3URL(_, nUri) }
          URISummary(imageUrlOpt, description)
        }
      } else future{embedlyResultOpt getOrElse URISummary()}
    }
  }

  /**
   * Triggers screenshot update
   */
  def updateScreenshots(nUri: NormalizedURI) = fetchFromPagePeeker(nUri, ImageSize(0,0))

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
  private def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize = ImageSize(0,0), descriptionOnly: Boolean = false): Future[Option[URISummary]] = {
    embedlyClient.getEmbedlyInfo(nUri.url) flatMap { embedlyInfoOpt =>
      embedlyInfoOpt map { embedlyInfo =>
        nUri.id map { nUriId =>
          // Persist page info to the database
          updatePageInfo(embedlyInfo.toPageInfo(nUriId))
          if (descriptionOnly) {
            future{Some(URISummary(None, embedlyInfo.description))}
          } else {
            val images = embedlyInfo.buildImageInfo(nUriId)
            images.find(meetsSizeConstraint(_, minSize)) flatMap { selectedImageInfo =>
              // Intern images that have higher priority than the selected image
              images.takeWhile(!meetsSizeConstraint(_, minSize)) map { imageInfo =>
                imageInfo.url map { imageUrl =>
                  imageFetcher.fetchRawImage(imageUrl) map { rawImageOpt =>
                    rawImageOpt map { rawImage => internImage(imageInfo, rawImage, nUri) }
                  }
                }
              }
              // Intern and return selected image (and page description)
              selectedImageInfo.url map { imageUrl =>
                imageFetcher.fetchRawImage(imageUrl) map { rawImageOpt =>
                  val imageInfoOpt = rawImageOpt flatMap { rawImage =>
                    internImage(selectedImageInfo, rawImage, nUri)
                  }
                  Some(URISummary(imageInfoOpt flatMap { getS3URL(_,nUri) }, embedlyInfo.description))
                }
              }
            } getOrElse future{Some(URISummary(None, embedlyInfo.description))}
          }
        } getOrElse future{None}
      } getOrElse future{None}
    }
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
            db.readWrite { implicit session => normalizedUriRepo.save(nUri.copy(id = nUri.id, screenshotUpdatedAt = Some(clock.now))) }
          } else {
            log.error(s"Failed to update screenshots for normalized URI $nUriId}")
          }
          successfulCandidates find { meetsSizeConstraint(_, minSize) }
        }
      }
    } getOrElse future{None}
  }

  /**
   * Updates database with page info
   */
  private def updatePageInfo(info: PageInfo) = {
    db.readWrite { implicit session =>
      pageInfoRepo.getByUri(info.uriId) match {
        case Some(storedInfo) => db.readWrite { implicit session => pageInfoRepo.save(info.copy(id = storedInfo.id)) }
        case _ => pageInfoRepo.save(info)
      }
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

  //todo(martin) method to prune obsolete images from S3 (i.e. remove image if there is a newer image with at least the same size and priority)
}
