package com.keepit.scraper

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI._
import com.keepit.model._
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.extractor._
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.search.{ LangDetector, Article, ArticleStore }
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import scala.util.{ Try, Failure, Success }
import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.learning.porndetector.SlidingWindowPornDetector
import com.keepit.search.Lang
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
    httpFetcher: HttpFetcher,
    extractorFactory: ExtractorFactory,
    articleStore: ArticleStore,
    pornDetectorFactory: PornDetectorFactory,
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
        case None => Future.successful[Unit]()
        case Some(latestUri) =>
          if (latestUri.state != NormalizedURIStates.INACTIVE && latestUri.state != NormalizedURIStates.REDIRECTED) {
            shoeboxCommander.updateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
          } else Future.successful[Unit]()
      }
    }
  }

  private def shouldUpdateScreenshot(uri: NormalizedURI) = {
    uri.screenshotUpdatedAt exists { update =>
      Days.daysBetween(currentDateTime.withTimeAtStartOfDay, update.withTimeAtStartOfDay).getDays >= 5
    }
  }

  private def needReIndex(latestUri: NormalizedURI, redirectProcessedUri: NormalizedURI, article: Article, signature: Signature, info: ScrapeInfo): Boolean = {
    def titleChanged = latestUri.title != Option(article.title)
    def restrictionChanged = latestUri.restriction != redirectProcessedUri.restriction
    def scrapeFailed = latestUri.state == NormalizedURIStates.SCRAPE_FAILED
    def activeURI = latestUri.state == NormalizedURIStates.ACTIVE
    def signatureChanged = signature.similarTo(Signature(info.signature)) < (1.0d - config.changeThreshold * (schedulerConfig.intervalConfig.minInterval / info.interval))
    def differentCanonicalUrl = article.canonicalUrl.exists(_.equalsIgnoreCase(latestUri.url))
    titleChanged || restrictionChanged || scrapeFailed || activeURI || signatureChanged || differentCanonicalUrl
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

    // This is bad. This whole function could likely be replaced with one call to shoebox signaling that a
    // scrape has happened. Excellent cleanup task for anyone learning scraper architecture.

    @inline def postProcess(scrapedURI: NormalizedURI, article: Article, signature: Signature): Future[Option[String]] = {
      article.canonicalUrl.fold(Future.successful())(recordScrapedNormalization(latestUri, signature, _, article.alternateUrls)) flatMap { _ =>
        scrapedURI.id.fold(Future.successful[Option[String]](None))(id => shoeboxScraperClient.getUriImage(id))
      }
    }

    val Scraped(article, signature, redirects) = scraped

    processRedirects(latestUri, redirects) flatMap { updatedUri =>
      // article.title
      // article.destinationUrl
      if (updatedUri.state == NormalizedURIStates.REDIRECTED || updatedUri.normalization == Some(Normalization.MOVED)) {
        shoeboxCommander.saveScrapeInfo(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE)) map { _ => None }
      } else if (!needReIndex(latestUri, updatedUri, article, signature, info)) {
        shoeboxCommander.saveScrapeInfo(info.withDocumentUnchanged()) map { _ => None }
      } else {
        articleStore += (latestUri.id.get -> article)
        updateWordCountCache(latestUri.id.get, Some(article))
        for {
          scrapedURI <- shoeboxCommander.saveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))
          _ <- shoeboxCommander.saveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
          uriImage <- postProcess(scrapedURI, article, signature)
        } yield {
          log.info(s"[handleSuccessfulScraped] scrapedURI=${scrapedURI.toShortString} uriImage=${uriImage}")
          Some(article)
        }
      }
    }
  }

  private def handleNotScrapable(latestUri: NormalizedURI, notScrapable: NotScrapable, info: ScrapeInfo): Future[Option[Article]] = {
    val NotScrapable(destinationUrl, redirects) = notScrapable

    val unscrapableUriF = {
      shoeboxCommander.saveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged()) flatMap { _ =>
        processRedirects(latestUri, redirects) flatMap { updatedUri =>
          if (updatedUri.state == NormalizedURIStates.REDIRECTED || updatedUri.normalization == Some(Normalization.MOVED))
            Future.successful(updatedUri)
          else {
            shoeboxCommander.updateNormalizedURIState(updatedUri.id.get, NormalizedURIStates.UNSCRAPABLE) map { _ =>
              updatedUri.withState(NormalizedURIStates.UNSCRAPABLE)
            }
          }
        }
      }
    }
    unscrapableUriF map { unscrapableURI =>
      log.info(s"[handleNotScrapable] unscrapableURI=${unscrapableURI}")
      updateWordCountCache(unscrapableURI.id.get, None)
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

  private def runPornDetectorIfNecessary(normalizedUri: NormalizedURI, content: String, contentLang: Lang): Future[Unit] = {
    uriCommander.isNonSensitive(normalizedUri.url).map { nonSensitive =>
      if (!nonSensitive) {
        if (contentLang == Lang("en") && content.size > 100) {
          val detector = new SlidingWindowPornDetector(pornDetectorFactory())
          detector.isPorn(content.take(100000)) match {
            case true if normalizedUri.restriction == None => shoeboxCommander.updateURIRestriction(normalizedUri.id.get, Some(Restriction.ADULT)) // don't override other restrictions
            case false if normalizedUri.restriction == Some(Restriction.ADULT) => shoeboxCommander.updateURIRestriction(normalizedUri.id.get, None)
            case _ => Future.successful(())
          }
        }
      } else {
        log.debug(s"uri $normalizedUri is exempted from sensitive check!")
        Future.successful(())
      }
    }
  }

  private def fetch(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Future[ScraperResult] = {
    val url = URI.parse(normalizedUri.url).getOrElse(throw new Exception(s"url can not be parsed for $normalizedUri"))
    val extractor = extractorFactory(url)
    log.debug(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)
    httpFetcher.get(url, ifModifiedSince, proxy = proxyOpt) { input => extractor.process(input) } flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          uriCommander.isUnscrapable(url, fetchStatus.destinationUrl) flatMap { unscrapable =>
            if (unscrapable) {
              Future.successful(NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects))
            } else {
              val content = extractor.getContent
              val title = extractor.getTitle
              val description = extractor.getDescription
              val contentLang = description match {
                case Some(desc) => LangDetector.detect(content + " " + desc)
                case None => LangDetector.detect(content)
              }
              runPornDetectorIfNecessary(normalizedUri, content, contentLang) map { _ =>
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
                  destinationUrl = fetchStatus.destinationUrl)
                Scraped(article, extractor.getSignature, fetchStatus.redirects) tap { res =>
                  log.info(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
                }
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

  // Watch out: the NormalizedURI may come back as REDIRECTED
  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): Future[NormalizedURI] = {
    @inline def resolveAndRecord(redirects: Seq[HttpRedirect]): Future[NormalizedURI] = {
      val unrestrictedUri = removeRedirectRestriction(uri)
      HttpRedirect.resolve(uri.url, redirects).map { absoluteDestination =>
        val validRedirect = HttpRedirect(HttpStatus.SC_MOVED_PERMANENTLY, unrestrictedUri.url, absoluteDestination)
        log.debug(s"Found permanent $validRedirect for $uri")
        shoeboxCommander.recordPermanentRedirect(unrestrictedUri, validRedirect)
      } getOrElse {
        redirects.headOption.foreach(relativeRedirect => log.warn(s"Ignoring relative redirect $relativeRedirect for $uri"))
        Future.successful(unrestrictedUri)
      }
    }

    val relevantRedirects = redirects.dropWhile(!_.isLocatedAt(uri.url))

    relevantRedirects.headOption match {
      case Some(redirect) if redirect.isShortener => resolveAndRecord(relevantRedirects)
      case Some(redirect) if redirect.isPermanent =>
        hasFishy301(uri) flatMap { isFishy =>
          if (isFishy) {
            Future.successful(updateRedirectRestriction(uri, redirect))
          } else resolveAndRecord(relevantRedirects)
        }

      case Some(temporaryRedirect) =>
        Future.successful(updateRedirectRestriction(uri, temporaryRedirect))

      case None => // no redirects
        Future.successful(uri)
    }
  }

  private def removeRedirectRestriction(uri: NormalizedURI): NormalizedURI = uri.restriction match {
    case Some(restriction) if Restriction.redirects.contains(restriction) => uri.copy(restriction = None)
    case _ => uri
  }

  private def updateRedirectRestriction(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = {
    val restriction = Restriction.http(redirect.statusCode)
    if (Restriction.redirects.contains(restriction)) uri.copy(restriction = Some(restriction)) else removeRedirectRestriction(uri)
  }

  private def hasFishy301(movedUri: NormalizedURI): Future[Boolean] = {
    if (movedUri.restriction == Some(Restriction.http(301))) {
      Future.successful(true)
    } else {
      shoeboxCommander.getLatestKeep(movedUri.url).map { keepOpt =>
        keepOpt.filter(_.keptAt.isAfter(currentDateTime.minusHours(1))) match {
          case Some(recentKeep) if !KeepSource.bulk.contains(recentKeep.source) =>
            true
          case Some(importedBookmark) =>
            val parsedBookmarkUrl = URI.parse(importedBookmark.url).get
            val isFishy = (parsedBookmarkUrl.toString != movedUri.url) &&
              !httpFetcher.fetch(parsedBookmarkUrl)(httpFetcher.NO_OP).redirects.headOption.exists(_.isPermanent)
            log.info(s"[hasFishy301] ${importedBookmark.uriId} result: $isFishy, ${parsedBookmarkUrl.toString} vs ${movedUri.url}")
            isFishy
          case None =>
            false
        }
      }
    }
  }

  private def recordScrapedNormalization(uri: NormalizedURI, signature: Signature, canonicalUrl: String, alternateUrls: Set[String]): Future[Unit] = {
    sanitize(uri.url, canonicalUrl) match {
      case None => Future.successful(())
      case Some(properCanonicalUrl) =>
        val properAlternateUrls = alternateUrls.flatMap(sanitize(uri.url, _)) - uri.url - properCanonicalUrl
        shoeboxCommander.recordScrapedNormalization(uri.id.get, signature, properCanonicalUrl, Normalization.CANONICAL, properAlternateUrls)
    }
  }

  private def sanitize(baseUrl: String, canonicalUrl: String): Option[String] = {
    val quotedString = """"(.+)"""".r
    val actualTargetUrlOption = Option(canonicalUrl) collect {
      case quotedString(uriString) => uriString
      case uriString if uriString.nonEmpty => uriString
    }
    for {
      actualTargetUrl <- actualTargetUrlOption
      absoluteTargetUrl <- URI.absoluteUrl(baseUrl, actualTargetUrl)
      parsedTargetUri <- safelyParse(absoluteTargetUrl)
    } yield parsedTargetUri.toString()
  }

  private def safelyParse(uriString: String): Option[URI] = URI.parse(uriString) match {
    case Success(uri) =>
      Try { java.net.URI.create(uri.toString()) } match {
        case Success(_) => Some(uri)
        case Failure(e) =>
          log.error(s"uri parsing by java...URI failed: [$uriString]", e)
          None
      }
    case Failure(e) =>
      log.error(s"uri parsing by keepit...URI failed: [$uriString]", e)
      None
  }

}
