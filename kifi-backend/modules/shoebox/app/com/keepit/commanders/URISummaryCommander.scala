package com.keepit.commanders

import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.net.WebService
import com.keepit.common.performance._
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.logging.Logging
import com.google.inject.{ Singleton, Inject }
import org.apache.commons.lang3.RandomStringUtils
import scala.concurrent.Future
import com.keepit.model._
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.slick.Database
import java.awt.image.BufferedImage
import scala.util.{ Success, Failure }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.scraper.{ NormalizedURIRef, ScraperServiceClient }
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
    imageConfig: S3ImageConfig,
    pageInfoRepo: PageInfoRepo,
    db: Database,
    scraper: ScraperServiceClient,
    cortex: CortexServiceClient,
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

  def getDefaultURISummary(nUri: NormalizedURI, waiting: Boolean): Future[URISummary] = {
    val uriSummaryRequest = URISummaryRequest(nUri.id.get, ImageType.ANY, ImageSize(0, 0), withDescription = true, waiting = waiting, silent = false)
    getURISummaryForRequest(uriSummaryRequest, NormalizedURIRef(nUri.id.get, nUri.url, nUri.externalId))
  }

  /**
   * Gets an image for the given URI. It can be an image on the page or a screenshot, and there are no size restrictions
   * If no image is available, fetching is triggered (silent=false) but the promise is immediately resolved (waiting=false)
   */
  def getURIImage(nUri: NormalizedURI, minSizeOpt: Option[ImageSize] = None): Future[Option[String]] = {
    val minSize = minSizeOpt getOrElse ImageSize(0, 0)
    val request = URISummaryRequest(nUri.id.get, ImageType.ANY, minSize, false, false, false)
    getURISummaryForRequest(request, NormalizedURIRef(nUri.id.get, nUri.url, nUri.externalId)) map { _.imageUrl }
  }

  /**
   * URI summaries are "best effort", which means partial results can be returned (for example if a description is required
   * and only the image is found, the image should still be returned
   */
  def getURISummaryForRequest(request: URISummaryRequest): Future[URISummary] = {
    val nUri = db.readOnlyReplica { implicit session =>
      normalizedUriRepo.get(request.uriId)
    }
    getURISummaryForRequest(request, NormalizedURIRef(nUri.id.get, nUri.url, nUri.externalId))
  }

  private val consolidateFetchURISummary = new RequestConsolidator[NormalizedURIRef, Option[URISummary]](60.seconds)

  private def getURISummaryForRequest(request: URISummaryRequest, nUri: NormalizedURIRef): Future[URISummary] = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    val existingSummary = getExistingSummaryForRequest(request, nUri)
    if (isCompleteSummary(existingSummary, request) || request.silent) {
      Future.successful(existingSummary)
    } else {
      val fetchedSummaryFuture = consolidateFetchURISummary(nUri)(fetchSummaryForRequest)
      if (request.isCacheable) fetchedSummaryFuture.onSuccess {
        case None => // ignore
        case Some(fetchedSummary) =>
          uriSummaryCache.set(URISummaryKey(nUri.id), fetchedSummary)
      }
      if (request.waiting) fetchedSummaryFuture.imap(_.getOrElse(existingSummary)) else Future.successful(existingSummary)
    }
  }

  private def getExistingSummaryForRequest(request: URISummaryRequest, nUri: NormalizedURIRef): URISummary = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    val cachedSummary = if (request.isCacheable) uriSummaryCache.get(URISummaryKey(nUri.id)) else None
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
  private def getStoredSummaryForRequest(nUri: NormalizedURIRef, imageType: ImageType, minSize: ImageSize, withDescription: Boolean): URISummary = {
    val targetProvider = imageType match {
      case ImageType.ANY => None
      case ImageType.SCREENSHOT => Some(ImageProvider.PAGEPEEKER)
      case ImageType.IMAGE => Some(ImageProvider.EMBEDLY)
    }
    db.readOnlyReplica { implicit session =>
      val storedImageInfos = imageInfoRepo.getByUriWithPriority(nUri.id, minSize, targetProvider)
      val storedSummaryOpt = storedImageInfos flatMap { imageInfo =>
        if (withDescription) {
          val wordCountOpt = scraper.getURIWordCountOpt(nUri.id, nUri.url) // todo: Why is this done separately? Scraper should handle it internally.
          for {
            pageInfo <- pageInfoRepo.getByUri(nUri.id)
          } yield {
            URISummary(Some(getCDNURL(imageInfo)), pageInfo.title, pageInfo.description, imageInfo.width, imageInfo.height, wordCountOpt)
          }
        } else {
          Some(URISummary(imageUrl = Some(getCDNURL(imageInfo)), imageWidth = imageInfo.width, imageHeight = imageInfo.height))
        }
      }
      storedSummaryOpt getOrElse URISummary()
    }
  }

  /**
   * Retrieves URI summary data from external services (Embedly, PagePeeker)
   */
  val fetchPreviewLock = new ReactiveLock(5, Some(50))
  private def fetchSummaryForRequest(nUri: NormalizedURIRef): Future[Option[URISummary]] = {
    log.info(s"fetchSummaryForRequest for ${nUri.id} -> ${nUri.url}")
    val stopper = Stopwatch(s"fetching from scraper embedly info for ${nUri.id} -> ${nUri.url}")
    if (fetchPreviewLock.waiting > 250) { // Backlog is this deep, we're way behind.
      log.info(s"fetchSummaryForRequest denied for ${nUri.id}, ${nUri.url} because lock waiting is ${fetchPreviewLock.waiting}")
      Future.successful(None)
    } else {
      fetchPreviewLock.withLockFuture {
        log.info(s"fetchSummaryForRequest running for ${nUri.id}, ${nUri.url}, wait is ${fetchPreviewLock.waiting}")
        val future = fetchFromEmbedly(nUri)
        future.onComplete { res =>
          stopper.stop() tap { _ => log.info(stopper.toString) }
        }
        future
      }
    }
  }

  /**
   * Fetches images and page description from Embedly
   */
  private def fetchFromEmbedly(nUri: NormalizedURIRef): Future[Option[URISummary]] = {
    scraper.fetchAndPersistURIPreview(nUri.url).map { rawResp =>
      rawResp.map { resp =>
        val savedImages = db.readWrite(attempts = 3) { implicit session =>
          // PageInfo (title, desc, etc)
          val pageInfo = pageInfoRepo.getByUri(nUri.id) match {
            case Some(pi) =>
              pi.copy(
                uriId = nUri.id,
                title = resp.title,
                description = resp.description,
                authors = resp.authors,
                publishedAt = resp.publishedAt,
                safe = resp.safe,
                lang = resp.lang,
                faviconUrl = resp.faviconUrl
              )
            case None =>
              PageInfo(
                uriId = nUri.id,
                title = resp.title,
                description = resp.description,
                authors = resp.authors,
                publishedAt = resp.publishedAt,
                safe = resp.safe,
                lang = resp.lang,
                faviconUrl = resp.faviconUrl
              )
          }
          pageInfoRepo.save(pageInfo)

          // Images
          log.info(s"[usc] Persisting ${resp.images.map(_.sizes.length)} images: ${resp.images}")
          resp.images.map { images =>
            // todo handle existing images
            // Persist images largest to smallest
            images.sizes.sortBy(i => i.height + i.width).reverse.map { image =>
              val format = image.path.substring(image.path.lastIndexOf(".") + 1).toLowerCase match {
                case "png" => ImageFormat.PNG
                case "jpg" | "jpeg" => ImageFormat.JPG
                case "gif" => ImageFormat.GIF
                case _ => ImageFormat.UNKNOWN
              }
              log.info(s"[usc] Persisting Created obj for ${image.path}")
              ImageInfo(
                uriId = nUri.id,
                url = Some(image.originalUrl),
                caption = images.caption,
                width = Some(image.width),
                height = Some(image.height),
                size = None, // Not storing this anymore
                format = Some(format),
                provider = Some(ImageProvider.EMBEDLY),
                priority = Some(0), // Only storing primary image for now
                path = image.path
              )
            }.map(imageInfoRepo.save)
          }
        }

        val primaryImageOpt = savedImages.flatMap(_.headOption)

        // and to support the old API:
        URISummary(
          imageUrl = primaryImageOpt.map(getCDNURL),
          imageWidth = primaryImageOpt.flatMap(_.width),
          imageHeight = primaryImageOpt.flatMap(_.height),
          title = resp.title,
          description = resp.description,
          wordCount = None
        )
      }
    }
  }

  /**
   * Get S3 url for image info
   */
  private def getCDNURL(info: ImageInfo): String = {
    imageConfig.cdnBase + "/" + info.path
  }

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

  //todo(andrew) method to prune obsolete images from S3 (i.e. remove image if there is a newer image with at least the same size and priority)
}
