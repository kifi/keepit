package com.keepit.commanders

import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.net.WebService
import com.keepit.common.performance._

import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.logging.Logging
import com.google.inject.{ Singleton, Inject }
import scala.concurrent.Future
import java.util.concurrent.TimeoutException
import com.keepit.model._
import com.keepit.common.store.{ S3URIImageStore, ImageSize }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.slick.Database
import com.keepit.common.pagepeeker.PagePeekerClient
import java.awt.image.BufferedImage
import scala.util.{ Success, Failure }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.scraper.ScraperServiceClient
import com.keepit.scraper.embedly.EmbedlyStore
import com.keepit.common.db.Id
import com.keepit.cortex.CortexServiceClient
import com.keepit.search.ArticleStore
import com.keepit.scraper.embedly.EmbedlyKeyword
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.service.RequestConsolidator
import scala.concurrent.duration._
import com.keepit.common.core._

@Singleton
class URISummaryCommander @Inject() (
    normalizedUriRepo: NormalizedURIRepo,
    normalizedURIInterner: NormalizedURIInterner,
    imageInfoRepo: ImageInfoRepo,
    pageInfoRepo: PageInfoRepo,
    db: Database,
    scraper: ScraperServiceClient,
    cortex: CortexServiceClient,
    uriImageStore: S3URIImageStore,
    embedlyStore: EmbedlyStore,
    articleStore: ArticleStore,
    uriSummaryCache: URISummaryCache,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    val webService: WebService) extends Logging with ProcessedImageHelper {

  /**
   * Gets the default URI Summary
   */
  def getDefaultURISummary(uriId: Id[NormalizedURI], waiting: Boolean): Future[URISummary] = {
    val uri = db.readOnlyReplica { implicit session => normalizedUriRepo.get(uriId) }
    getDefaultURISummary(uri, waiting)
  }

  def getDefaultURISummary(uri: NormalizedURI, waiting: Boolean): Future[URISummary] = {
    val uriSummaryRequest = URISummaryRequest(uri.id.get, ImageType.ANY, ImageSize(0, 0), withDescription = true, waiting = waiting, silent = false)
    getURISummaryForRequest(uriSummaryRequest, uri)
  }

  /**
   * Gets an image for the given URI. It can be an image on the page or a screenshot, and there are no size restrictions
   * If no image is available, fetching is triggered (silent=false) but the promise is immediately resolved (waiting=false)
   */
  def getURIImage(nUri: NormalizedURI, minSizeOpt: Option[ImageSize] = None): Future[Option[String]] = {
    val minSize = minSizeOpt getOrElse ImageSize(0, 0)
    val request = URISummaryRequest(nUri.id.get, ImageType.ANY, minSize, false, false, false)
    getURISummaryForRequest(request, nUri) map { _.imageUrl }
  }

  /**
   * URI summaries are "best effort", which means partial results can be returned (for example if a description is required
   * and only the image is found, the image should still be returned
   */
  def getURISummaryForRequest(request: URISummaryRequest): Future[URISummary] = {
    val nUri = db.readOnlyReplica { implicit session =>
      normalizedUriRepo.get(request.uriId)
    }
    getURISummaryForRequest(request, nUri)
  }

  //todo(Léo): swap url for uriId in URISummaryRequest and stop propagating the entire NormalizedURI in this code
  private val consolidateFetchURISummary = new RequestConsolidator[(NormalizedURI, ImageType, ImageSize, Boolean), Option[URISummary]](20 seconds)

  def getURISummaryForRequest(request: URISummaryRequest, nUri: NormalizedURI): Future[URISummary] = {
    val existingSummary = getExistingSummaryForRequest(request, nUri)
    if (isCompleteSummary(existingSummary, request) || request.silent) {
      Future.successful(existingSummary)
    } else {
      val fetchedSummaryFuture = consolidateFetchURISummary((nUri, request.imageType, request.minSize, request.withDescription)) {
        case (nUri, imageType, minSize, withDescription) => fetchSummaryForRequest(nUri, imageType, minSize, withDescription)
      }
      if (request.isCacheable) fetchedSummaryFuture.onSuccess {
        case None => // ignore
        case Some(fetchedSummary) =>
          import TransactionalCaching.Implicits.directCacheAccess
          uriSummaryCache.set(URISummaryKey(nUri.id.get), fetchedSummary)
      }
      if (request.waiting) fetchedSummaryFuture.imap(_.getOrElse(existingSummary)) else Future.successful(existingSummary)
    }
  }

  private def getExistingSummaryForRequest(request: URISummaryRequest, nUri: NormalizedURI): URISummary = {
    import TransactionalCaching.Implicits.directCacheAccess
    val cachedSummary = if (request.isCacheable) uriSummaryCache.get(URISummaryKey(nUri.id.get)) else None
    cachedSummary getOrElse timing(s"getStoredSummaryForRequest ${nUri.id} -> ${nUri.url}") {
      getStoredSummaryForRequest(nUri, request.imageType, request.minSize, request.withDescription)
    }
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
    db.readOnlyReplica { implicit session =>
      val storedImageInfos = nUri.id flatMap { id =>
        imageInfoRepo.getByUriWithPriority(id, minSize, targetProvider)
      }
      val storedSummaryOpt = storedImageInfos flatMap { imageInfo =>
        if (withDescription) {
          val wordCountOpt = nUri.id flatMap { id =>
            scraper.getURIWordCountOpt(id, Some(nUri.url))
          }
          for {
            nUriId <- nUri.id
            pageInfo <- pageInfoRepo.getByUri(nUriId)
          } yield {
            URISummary(getS3URL(imageInfo, nUri), pageInfo.title, pageInfo.description, imageInfo.width, imageInfo.height, wordCountOpt)
          }
        } else {
          Some(URISummary(imageUrl = getS3URL(imageInfo, nUri), imageWidth = imageInfo.width, imageHeight = imageInfo.height))
        }
      }
      storedSummaryOpt getOrElse URISummary()
    }
  }

  /**
   * Retrieves URI summary data from external services (Embedly, PagePeeker)
   */
  private def fetchSummaryForRequest(nUri: NormalizedURI, imageType: ImageType, minSize: ImageSize, withDescription: Boolean): Future[Option[URISummary]] = {
    log.info(s"fetchSummaryForRequest for ${nUri.id} -> ${nUri.url}")
    val embedlyResultFut = if (imageType == ImageType.IMAGE || imageType == ImageType.ANY || withDescription) {
      val stopper = Stopwatch("fetching from scraper embedly info for ${nUri.id} -> ${nUri.url}")
      val future = fetchFromEmbedly(nUri, minSize)
      future.onComplete { res =>
        stopper.stop() tap { _ => log.info(stopper.toString) }
        res.map(_.map(s => resizeImage(nUri, s)))
      }
      future
    } else {
      Future.successful(None)
    }
    embedlyResultFut
  }

  val resizeLock = new ReactiveLock(2)
  private def resizeImage(nUri: NormalizedURI, uriSummary: URISummary) = {
    uriSummary
  }

  /**
   * Fetches images and/or page description from Embedly. The retrieved information is persisted to the database
   */
  private def fetchFromEmbedly(nUri: NormalizedURI, minSize: ImageSize = ImageSize(0, 0), descriptionOnly: Boolean = false): Future[Option[URISummary]] = {
    try {
      scraper.getURISummaryFromEmbedly(nUri, minSize, descriptionOnly)
    } catch {
      case timeout: TimeoutException =>
        val failImageInfo = db.readWrite { implicit session =>
          imageInfoRepo.save(ImageInfo(uriId = nUri.id.get, url = Some(nUri.url), provider = Some(ImageProvider.EMBEDLY), format = Some(ImageFormat.UNKNOWN)))
        }
        airbrake.notify(s"Could not fetch from embedly because of timeout, persisting a tombstone for the image in $failImageInfo", timeout)
        Future.successful(None)
    }
  }

  /**
   * Get S3 url for image info
   */
  private def getS3URL(info: ImageInfo, nUri: NormalizedURI): Option[String] = uriImageStore.getImageURL(info, nUri)

  def getStoredEmbedlyKeywords(id: Id[NormalizedURI]): Seq[EmbedlyKeyword] = {
    embedlyStore.get(id) match {
      case Some(info) => info.info.keywords.sortBy(-1 * _.score)
      case None => Seq()
    }
  }

  def getArticleKeywords(id: Id[NormalizedURI]): Seq[String] = {

    val rv = for {
      article <- articleStore.get(id)
      keywords <- article.keywords
    } yield {
      keywords.toLowerCase.split(" ").filter { x => !x.isEmpty && x.forall(_.isLetterOrDigit) }
    }

    rv.getOrElse(Array()).toSeq
  }

  def getWord2VecKeywords(id: Id[NormalizedURI]): Future[Option[Word2VecKeywords]] = {
    cortex.word2vecURIKeywords(id)
  }

  def batchGetWord2VecKeywords(ids: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]] = {
    cortex.word2vecBatchURIKeywords(ids)
  }

  // FYI this is very slow. Be careful calling it.
  def getKeywordsSummary(uri: Id[NormalizedURI]): Future[KeywordsSummary] = {
    val word2vecKeywordsFut = getWord2VecKeywords(uri)
    val embedlyKeywords = getStoredEmbedlyKeywords(uri)
    val embedlyKeywordsStr = embedlyKeywords.map { _.name }.toSet
    val articleKeywords = getArticleKeywords(uri).toSet

    for {
      word2vecKeys <- word2vecKeywordsFut
    } yield {

      val word2vecCount = word2vecKeys.map { _.wordCounts } getOrElse 0
      val w2vCos = word2vecKeys.map { _.cosine.toSet } getOrElse Set()
      val w2vFreq = word2vecKeys.map { _.freq.toSet } getOrElse Set()

      val bestGuess = if (!articleKeywords.isEmpty) {
        articleKeywords intersect (embedlyKeywordsStr union w2vCos union w2vFreq)
      } else {
        if (embedlyKeywords.isEmpty) {
          w2vCos intersect w2vFreq
        } else {
          embedlyKeywordsStr intersect (w2vCos union w2vFreq)
        }
      }

      KeywordsSummary(articleKeywords.toSeq, embedlyKeywords, w2vCos.toSeq, w2vFreq.toSeq, word2vecCount, bestGuess.toSeq)
    }

  }

  //todo(martin) method to prune obsolete images from S3 (i.e. remove image if there is a newer image with at least the same size and priority)
}
