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

case class URIImageRequest(
  url: String,
  imageType: ImageType,
  minSize: ImageSize,
  requestSource: RequestSource,
  waiting: Boolean,
  silent: Boolean)

class URIImageCommander @Inject()(
  normalizedUriRepo: NormalizedURIRepo,
  imageInfoRepo: ImageInfoRepo,
  db: Database,
  embedlyClient: EmbedlyClient,
  pagePeekerClient: PagePeekerClient,
  uriImageStore: S3URIImageStore,
  imageFetcher: ImageFetcher,
  airbrake: AirbrakeNotifier
) extends Logging {

  def getImageForURL(value: JsValue, source: RequestSource): Future[JsValue] = {
    parseRequest(value, source) match {
      case Left(request) => {
        val genericError = jsonError("no_image_found")
        getNormalizedURIForRequest(request).map { nUri =>
          getImageForURLRequest(request, nUri) map { imageInfoOpt =>
            imageInfoOpt match {
              case Some(imageInfo) => Json.obj("url" -> uriImageStore.mkImgUrl(nUri.externalId, imageInfo.provider, imageInfo.name))
              case None => genericError
            }
          }
        } getOrElse future{genericError}
      }
      case Right(error) => future {jsonError(error)}
    }
  }

  private def parseRequest(value: JsValue, source: RequestSource): Either[URIImageRequest,String] = {
    (value \ "url").asOpt[String] map { url =>
      (value \ "width").asOpt[Int] map { width =>
        (value \ "height").asOpt[Int] map { height =>
          val imageType = (value \ "type").asOpt[ImageType].getOrElse(ImageType.ANY)
          val waiting = (value \ "waiting").asOpt[Boolean].getOrElse(true)
          val silent = (value \ "silent").asOpt[Boolean].getOrElse(false)
          Left(URIImageRequest(url, imageType, ImageSize(width, height), RequestSource.EXTENSION, waiting, silent))
        } getOrElse Right("missing_min_height")
      } getOrElse Right("missing_min_width")
    } getOrElse Right("missing_url")
  }

  private def jsonError(message: String) = Json.obj("error" -> message)

  def getNormalizedURIForRequest(request: URIImageRequest): Option[NormalizedURI] = {
    if (request.silent)
      db.readOnly { implicit session => normalizedUriRepo.getByUri(request.url) }
    else
      db.readWrite { implicit session => Some(normalizedUriRepo.internByUri(request.url)) }
  }

  def getImageForURLRequest(request: URIImageRequest): Future[Option[ImageInfo]] = {
    getNormalizedURIForRequest(request) map { nUri =>
      getImageForURLRequest(request, nUri)
    } getOrElse (future{None})
  }

  private def getImageForURLRequest(request: URIImageRequest, nUri: NormalizedURI): Future[Option[ImageInfo]] = {
    val imageInfoOpt = getStoredImageForURI(nUri, request.imageType, request.minSize)
    if (imageInfoOpt.isEmpty) {
      if (request.waiting || !request.silent) fetchImagesForURLRequest(nUri, request.imageType, request.minSize) else future{None}
    } else future{imageInfoOpt}
  }

  private def getStoredImageForURI(pageUri: NormalizedURI, imageType: ImageType, minSize: ImageSize): Option[ImageInfo] = {
    val targetProvider = imageType match {
      case ImageType.ANY => None
      case ImageType.SCREENSHOT => Some(ImageProvider.PAGEPEEKER)
      case ImageType.IMAGE => Some(ImageProvider.EMBEDLY)
    }
    pageUri.id map { id => db.readOnly { implicit session => imageInfoRepo.getByUriWithSize(id, minSize) } } flatMap { list =>
      val candidates = list.filter(targetProvider.isEmpty || targetProvider == _.provider)
      if (candidates.nonEmpty) Some(candidates.minBy(_.priority.getOrElse(Int.MaxValue))) else None
    }
  }

  private def fetchImagesForURLRequest(pageId: NormalizedURI, imageType: ImageType, minSize: ImageSize): Future[Option[ImageInfo]] = {
    val embedlyResultFut = if (imageType == ImageType.IMAGE || imageType == ImageType.ANY) fetchFromEmbedly(pageId, minSize)
    else future{None}
    embedlyResultFut flatMap { imageResult =>
      if (imageResult.isEmpty && (imageType == ImageType.SCREENSHOT || imageType == ImageType.ANY)) {
        fetchFromPagePeeker(pageId, minSize)
      } else future{imageResult}
    }
  }

  private def fetchFromEmbedly(pageId: NormalizedURI, minSize: ImageSize): Future[Option[ImageInfo]] = {
    embedlyClient.getAllImageInfo(pageId, minSize) flatMap { images =>
      images.find(meetsSizeConstraint(_, minSize)) map { selectedImageInfo =>
        // Intern images that have higher priority than the selected image
        images.takeWhile(!meetsSizeConstraint(_, minSize)) map { imageInfo =>
          imageInfo.url map { imageUrl =>
            imageFetcher.fetchRawImage(imageUrl) map { rawImageOpt =>
              rawImageOpt map { rawImage => internImage(imageInfo, rawImage, pageId) }
            }
          }
        }
        // Intern and return selected image
        selectedImageInfo.url map { imageUrl =>
          imageFetcher.fetchRawImage(imageUrl) map { rawImageOpt =>
            rawImageOpt flatMap { rawImage =>
              internImage(selectedImageInfo, rawImage, pageId)
            }
          }
        } getOrElse future{None}
      } getOrElse future{None}
    }
  }

  private def fetchFromPagePeeker(nUri: NormalizedURI, minSize: ImageSize): Future[Option[ImageInfo]] = {
    nUri.id map { nUriId =>
      pagePeekerClient.getScreenshotData(nUri.url) map { images =>
        val candidates = images map { image =>
          val imageInfo = image.toImageInfo(nUriId)
          internImage(imageInfo, image.rawImage, nUri)
        }
        candidates collect { case Some(candidate) => candidate } find { meetsSizeConstraint(_, minSize) }
      }
    } getOrElse future{None}
  }

  private def internImage(info: ImageInfo, image: BufferedImage, nUri: NormalizedURI): Option[ImageInfo] = {
    uriImageStore.storeImage(info, image, nUri) match {
      case Success(result) => {
        val (url,size) = result
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

  private def meetsSizeConstraint(image: ImageInfo, size: ImageSize): Boolean = {
    for {
      width <- image.width
      height <- image.height
    } yield {
      return (width > size.width && height > size.height)
    }
    false
  }

  //todo(martin) method to prune obsolete images from S3 (i.e. remove image if there is a newer image with at least the same size and priority)
}
