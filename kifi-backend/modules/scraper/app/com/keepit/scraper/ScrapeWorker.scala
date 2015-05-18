package com.keepit.scraper

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.fetcher.FetchRequest
import com.keepit.scraper.extractor._
import com.keepit.scraper.fetcher.DeprecatedHttpFetcher
import com.keepit.search.{ LangDetector, Article, ArticleStore }
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import scala.util.Success
import com.keepit.shoebox.ShoeboxScraperClient
import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.scraper.embedly.EmbedlyCommander
import com.keepit.common.core._

@ImplementedBy(classOf[ScrapeWorkerImpl])
trait ScrapeWorker {
  def safeProcess(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Future[Option[Article]]
}

class ScrapeWorkerImpl @Inject() (
    airbrake: AirbrakeNotifier,
    config: ScraperConfig,
    schedulerConfig: ScraperSchedulerConfig,
    httpFetcher: DeprecatedHttpFetcher,
    extractorFactory: ExtractorFactory,
    articleStore: ArticleStore,
    uriCommander: URICommander,
    shoeboxCommander: ShoeboxCommander,
    shoeboxScraperClient: ShoeboxScraperClient,
    urlCommander: URICommander,
    wordCountCache: NormalizedURIWordCountCache,
    uriSummaryCache: URISummaryCache,
    embedlyCommander: EmbedlyCommander) extends ScrapeWorker with Logging {

  implicit val myConfig = config
  implicit val scheduleConfig = schedulerConfig
  implicit val fj = ExecutionContext.fj
  val awaitTTL = (myConfig.syncAwaitTimeout seconds)

  def safeProcess(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Future[Option[Article]] = {
    process(uri, info, pageInfoOpt, proxyOpt) recoverWith {
      case t: Throwable =>
        airbrake.notify(t)
        recordScrapeFailure(uri) flatMap { _ =>
          shoeboxCommander.saveScrapeInfo(info.withFailure()) map { _ => None }
        }
    }
  }

  private def recordScrapeFailure(uri: NormalizedURI): Future[Unit] = {
    shoeboxCommander.getNormalizedUri(uri).flatMap { latestUriOpt =>
      latestUriOpt match {
        case None => Future.successful(())
        case Some(latestUri) =>
          if (latestUri.state != NormalizedURIStates.INACTIVE && latestUri.state != NormalizedURIStates.REDIRECTED) {
            shoeboxCommander.updateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
          } else Future.successful(())
      }
    }
  }

  private def needReIndex(latestUri: NormalizedURI, article: Article, signature: Signature, info: ScrapeInfo): Boolean = {
    def titleChanged = latestUri.title != Option(article.title)
    def scrapeFailed = latestUri.state == NormalizedURIStates.SCRAPE_FAILED
    def activeURI = latestUri.state == NormalizedURIStates.ACTIVE
    def signatureChanged = signature.similarTo(Signature(info.signature)) < (1.0d - config.changeThreshold * (schedulerConfig.intervalConfig.minInterval / info.interval))
    def differentCanonicalUrl = article.canonicalUrl.exists(_.equalsIgnoreCase(latestUri.url))
    titleChanged || scrapeFailed || activeURI || signatureChanged || differentCanonicalUrl
  }

  private def updateWordCountCache(uriId: Id[NormalizedURI], article: Option[Article]) = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    val count = article match {
      case Some(a) => a.content.split(" ").count(!_.isEmpty)
      case None => -1
    }

    log.info(s"updating wordCount cache for uriId = $uriId, word count = $count")
    wordCountCache.set(NormalizedURIWordCountKey(uriId), count)

    uriSummaryCache.get(URISummaryKey(uriId)) match {
      case Some(summary) => uriSummaryCache.set(URISummaryKey(uriId), summary.copy(wordCount = Some(count)))
      case None =>
    }
  }

  private def handleSuccessfulScraped(latestUri: NormalizedURI, scraped: Scraped, info: ScrapeInfo, pageInfoOpt: Option[PageInfo]): Future[Option[Article]] = {

    val uriId = latestUri.id.get

    @inline def postProcess(uriId: Id[NormalizedURI], article: Article, signature: Signature): Future[Option[String]] = {
      shoeboxScraperClient.getUriImage(uriId)
    }

    val Scraped(article, signature, _) = scraped
    // article.title
    // article.destinationUrl
    if (latestUri.state == NormalizedURIStates.REDIRECTED || latestUri.normalization == Some(Normalization.MOVED)) {
      shoeboxCommander.saveScrapeInfo(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE)) map { _ => None }
    } else if (!needReIndex(latestUri, article, signature, info)) {
      shoeboxCommander.saveScrapeInfo(info.withDocumentUnchanged()) map { _ => None }
    } else {
      articleStore += (uriId -> article)
      updateWordCountCache(uriId, Some(article))
      for {
        _ <- shoeboxCommander.updateNormalizedURIState(uriId, NormalizedURIStates.SCRAPED)
        _ <- shoeboxCommander.saveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
        uriImage <- postProcess(uriId, article, signature)
      } yield {
        log.info(s"[handleSuccessfulScraped] scrapedURI=${latestUri.toShortString} uriImage=${uriImage}")
        Some(article)
      }
    }
  }

  private def handleNotScrapable(latestUri: NormalizedURI, notScrapable: NotScrapable, info: ScrapeInfo): Future[Option[Article]] = {
    val NotScrapable(destinationUrl, _) = notScrapable
    val uriId = latestUri.id.get

    val unscrapableUriF = {
      shoeboxCommander.saveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged()) flatMap { _ =>
        if (latestUri.state == NormalizedURIStates.REDIRECTED || latestUri.normalization == Some(Normalization.MOVED))
          Future.successful(())
        else {
          shoeboxCommander.updateNormalizedURIState(uriId, NormalizedURIStates.UNSCRAPABLE)
        }
      }
    }
    unscrapableUriF map { unscrapableURI =>
      log.info(s"[handleNotScrapable] unscrapableURI=${unscrapableURI}")
      updateWordCountCache(uriId, None)
      None
    }
  }

  private def handleNotModified(url: String, info: ScrapeInfo): Future[Option[Article]] = {
    shoeboxCommander.saveScrapeInfo(info.withDocumentUnchanged()) map { _ => None }
  }

  private def handleScrapeError(latestUri: NormalizedURI, error: Error, info: ScrapeInfo): Future[Option[Article]] = {
    val Error(httpStatus, msg) = error
    // store a fallback article in a store map
    val article = Article(
      id = latestUri.id.get,
      title = latestUri.title.getOrElse(""),
      description = None,
      author = None,
      publishedAt = None,
      canonicalUrl = None,
      alternateUrls = Set.empty,
      keywords = None,
      media = None,
      content = "",
      scrapedAt = currentDateTime,
      httpContentType = None,
      httpOriginalContentCharset = None,
      state = NormalizedURIStates.SCRAPE_FAILED,
      message = Option(msg),
      titleLang = None,
      contentLang = None,
      destinationUrl = None)
    articleStore += (latestUri.id.get -> article)

    // the article is saved. update the scrape schedule and the state to SCRAPE_FAILED and save
    shoeboxCommander.saveScrapeInfo(info.withFailure()) flatMap { _ =>
      shoeboxCommander.updateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED) map { _ =>
        log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${latestUri.id}, ${latestUri.state}, ${latestUri.url})")
        updateWordCountCache(latestUri.id.get, None)
        None
      }
    }
  }

  private def callEmbedly(uri: NormalizedURI): Unit = {
    embedlyCommander.fetchEmbedlyInfo(uri.id.get, uri.url)
  }

  private def process(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Future[Option[Article]] = {
    val resF = safeFetch(uri, info, proxyOpt) flatMap { scraperResult =>
      shoeboxCommander.getNormalizedUri(uri) flatMap { uriOpt =>
        val articleOpt = uriOpt match {
          case None => Future.successful(None)
          case Some(latestUri) =>
            callEmbedly(latestUri)
            if (latestUri.state == NormalizedURIStates.INACTIVE)
              Future.successful(None)
            else {
              scraperResult match {
                case scraped: Scraped => handleSuccessfulScraped(latestUri, scraped, info, pageInfoOpt)
                case notScrapable: NotScrapable => handleNotScrapable(latestUri, notScrapable, info)
                case NotModified => handleNotModified(latestUri.url, info)
                case error: Error => handleScrapeError(latestUri, error, info)
              }
            }
        }
        articleOpt
      }
    }
    resF
  }

  private def safeFetch(normalizedUri: NormalizedURI, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Future[ScraperResult] = {
    val scrapeResultFuture = URI.parse(normalizedUri.url) match {
      case Success(uri) =>
        uri.scheme match {
          case Some("file") => Future.successful(Error(-1, "forbidden scheme: %s".format("file")))
          case _ => fetch(normalizedUri, httpFetcher, info, proxyOpt)
        }
      case _ => fetch(normalizedUri, httpFetcher, info, proxyOpt)
    }
    scrapeResultFuture recoverWith {
      case t: Throwable =>
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString("|")}")
        fetch(normalizedUri, httpFetcher, info, proxyOpt)
    }
  }

  private def getIfModifiedSince(uri: NormalizedURI, info: ScrapeInfo) = {
    if (uri.state == NormalizedURIStates.SCRAPED) {
      info.signature match {
        case "" => None // no signature. this is the first time
        case _ => Some(info.lastScrape)
      }
    } else {
      None
    }
  }

  private def fetch(normalizedUri: NormalizedURI, httpFetcher: DeprecatedHttpFetcher, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Future[ScraperResult] = {
    val extractor = {
      val parsedUrl = URI.parse(normalizedUri.url).getOrElse(throw new Exception(s"url can not be parsed for $normalizedUri"))
      extractorFactory(parsedUrl)
    }
    log.debug(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val fetchRequest = {
      val ifModifiedSince = getIfModifiedSince(normalizedUri, info)
      FetchRequest(normalizedUri.url, proxyOpt, ifModifiedSince)
    }
    httpFetcher.get(fetchRequest) { input => extractor.process(input) } flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          uriCommander.isUnscrapable(normalizedUri.url, fetchStatus.destinationUrl) map { unscrapable =>
            if (unscrapable) {
              NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
            } else {
              val content = extractor.getContent
              val title = extractor.getTitle
              val description = extractor.getDescription
              val contentLang = description match {
                case Some(desc) => LangDetector.detect(content + " " + desc)
                case None => LangDetector.detect(content)
              }
              val article: Article = Article(
                id = normalizedUri.id.get,
                title = title,
                description = description,
                author = extractor.getAuthor,
                publishedAt = extractor.getPublishedAt,
                canonicalUrl = extractor.getCanonicalUrl(normalizedUri.url),
                alternateUrls = extractor.getAlternateUrls,
                keywords = extractor.getKeywords,
                media = extractor.getMediaTypeString,
                content = content,
                scrapedAt = currentDateTime,
                httpContentType = extractor.getMetadata("Content-Type"),
                httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
                state = NormalizedURIStates.SCRAPED,
                message = None,
                titleLang = Some(LangDetector.detect(title, contentLang)), // bias detection using content language
                contentLang = Some(contentLang),
                destinationUrl = fetchStatus.destinationUrl
              )
              Scraped(article, extractor.getSignature, fetchStatus.redirects) tap { res =>
                log.info(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
              }
            }
          }
        case HttpStatus.SC_NOT_MODIFIED =>
          Future.successful(com.keepit.scraper.NotModified)
        case _ =>
          Future.successful(Error(fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed")))
      }
    } recover {
      case e: Throwable => {
        log.error(s"[fetchArticle] fetch failed ${normalizedUri.url} $info $httpFetcher;\nException: $e; Cause: ${e.getCause}")
        Error(-1, "fetch failed: %s".format(e.toString))
      }
    }
  }
}
