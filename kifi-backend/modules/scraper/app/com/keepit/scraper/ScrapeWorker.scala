package com.keepit.scraper

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.search.{ LangDetector, Article, ArticleStore }
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import scala.util.Success
import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.learning.porndetector.SlidingWindowPornDetector
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.search.Lang
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.scraper.embedly.EmbedlyCommander

@ImplementedBy(classOf[ScrapeWorkerImpl])
trait ScrapeWorker {
  def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Option[Article]
}

class ScrapeWorkerImpl @Inject() (
    airbrake: AirbrakeNotifier,
    config: ScraperConfig,
    schedulerConfig: ScraperSchedulerConfig,
    httpFetcher: HttpFetcher,
    extractorFactory: ExtractorFactory,
    articleStore: ArticleStore,
    pornDetectorFactory: PornDetectorFactory,
    helper: SyncShoeboxDbCallbacks,
    shoeboxClient: ShoeboxServiceClient,
    wordCountCache: NormalizedURIWordCountCache,
    uriSummaryCache: URISummaryCache,
    embedlyCommander: EmbedlyCommander) extends ScrapeWorker with Logging {

  implicit val myConfig = config
  implicit val scheduleConfig = schedulerConfig
  val awaitTTL = (myConfig.syncAwaitTimeout seconds)

  def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Option[Article] = try {
    processURI(uri, info, pageInfoOpt, proxyOpt)
  } catch {
    case t: Throwable => {
      log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString("|")}")
      airbrake.notify(t)

      recordScrapeFailed(uri)
      helper.syncSaveScrapeInfo(info.withFailure()) // then update the scrape schedule
      None
    }
  }

  private def recordScrapeFailed(uri: NormalizedURI): Unit = {
    helper.syncGetNormalizedUri(uri).foreach { latestUri =>
      if (latestUri.state != NormalizedURIStates.INACTIVE && latestUri.state != NormalizedURIStates.REDIRECTED) {
        helper.syncUpdateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
      }
    }
  }

  private def shouldUpdateImage(uri: NormalizedURI, scrapedURI: NormalizedURI, pageInfoOpt: Option[PageInfo]): Boolean = {
    if (NormalizedURIStates.DO_NOT_SCRAPE.contains(scrapedURI.state)) {
      log.warn(s"[shouldUpdateImage(${uri.id},${uri.state},${uri.url})] DO_NOT_SCRAPE; skipped.")
      false
    } else {
      pageInfoOpt match {
        case None =>
          // may need marker if embedly fails
          log.debug(s"[shouldUpdateImage(${uri.id},${uri.state},${uri.url})] pageInfo=None; update.")
          true
        case Some(pageInfo) =>
          if (Days.daysBetween(currentDateTime, pageInfo.updatedAt).getDays >= 5) {
            log.debug(s"[shouldUpdateImage(${uri.id},${uri.state},${uri.url})] it's been 5 days; pageInfo=$pageInfo; update.")
            true
          } else {
            log.debug(s"[shouldUpdateImage(${uri.id},${uri.state},${uri.url})] it's not been 5 days; pageInfo=$pageInfo; skipped.")
            false
          }
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

    titleChanged || restrictionChanged || scrapeFailed || activeURI || signatureChanged
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

  private def handleSuccessfulScraped(latestUri: NormalizedURI, scraped: Scraped, info: ScrapeInfo, pageInfoOpt: Option[PageInfo]): Option[Article] = {
    val Scraped(article, signature, redirects) = scraped
    val updatedUri = processRedirects(latestUri, redirects)

    if (updatedUri.state == NormalizedURIStates.REDIRECTED || updatedUri.normalization == Some(Normalization.MOVED)) {
      helper.syncSaveScrapeInfo(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
      None
    } else if (!needReIndex(latestUri, updatedUri, article, signature, info)) {
      helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
      log.debug(s"[processURI] (${latestUri.url}) no change detected")
      None
    } else {

      articleStore += (latestUri.id.get -> article)
      updateWordCountCache(latestUri.id.get, Some(article))

      val scrapedURI = helper.syncSaveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))

      // scrape schedule
      helper.syncSaveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))

      helper.syncGetBookmarksByUriWithoutTitle(scrapedURI.id.get).foreach { bookmark =>
        helper.syncSaveBookmark(bookmark.copy(title = scrapedURI.title))
      }

      // Report canonical url
      article.canonicalUrl.foreach(recordScrapedNormalization(latestUri, signature, _, article.alternateUrls))

      log.debug(s"[processURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

      if (shouldUpdateScreenshot(scrapedURI)) {
        scrapedURI.id map shoeboxClient.updateScreenshots
      }

      if (shouldUpdateImage(latestUri, scrapedURI, pageInfoOpt)) {
        scrapedURI.id map { id =>
          shoeboxClient.getUriImage(id) map { res =>
            log.info(s"[processURI(${latestUri.id},${latestUri.url})] (asyncGetImageUrl) imageUrl=$res")
          }
        }
      }

      log.debug(s"[processURI] (${latestUri.url}) needs re-indexing; scrapedURI=(${scrapedURI.id}, ${scrapedURI.state}, ${scrapedURI.url})")
      Some(article)
    }
  }

  private def handleNotScrapable(latestUri: NormalizedURI, notScrapable: NotScrapable, info: ScrapeInfo): Option[Article] = {
    val NotScrapable(destinationUrl, redirects) = notScrapable

    val unscrapableURI = {
      helper.syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
      val updatedUri = processRedirects(latestUri, redirects)
      if (updatedUri.state == NormalizedURIStates.REDIRECTED || updatedUri.normalization == Some(Normalization.MOVED)) updatedUri
      else {
        helper.syncUpdateNormalizedURIState(updatedUri.id.get, NormalizedURIStates.UNSCRAPABLE)
        updatedUri.withState(NormalizedURIStates.UNSCRAPABLE)
      }
    }
    log.debug(s"[processURI] (${latestUri.url}) not scrapable; unscrapableURI=(${unscrapableURI.id}, ${unscrapableURI.state}, ${unscrapableURI.url}})")

    updateWordCountCache(unscrapableURI.id.get, None)

    None
  }

  private def handleNotModified(url: String, info: ScrapeInfo): Option[Article] = {
    // update the scrape schedule, uri is not changed
    val updatedInfo = helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
    log.debug(s"[processURI] ($url not modified; updatedInfo=($updatedInfo)")
    None
  }

  private def handleScrapeError(latestUri: NormalizedURI, error: Error, info: ScrapeInfo): Option[Article] = {

    val Error(httpStatus, msg) = error
    // store a fallback article in a store map
    val article = Article(
      id = latestUri.id.get,
      title = latestUri.title.getOrElse(""),
      description = None,
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
    helper.syncSaveScrapeInfo(info.withFailure())
    helper.syncUpdateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
    log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${latestUri.id}, ${latestUri.state}, ${latestUri.url})")
    updateWordCountCache(latestUri.id.get, None)
    None
  }

  private def callEmbedly(uri: NormalizedURI): Unit = {
    embedlyCommander.fetchEmbedlyInfo(uri.id.get, uri.url)
  }

  private def processURI(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Option[Article] = {
    log.debug(s"[processURI] scraping ${uri.url} $info")
    val fetchedArticle = fetchArticle(uri, info, proxyOpt)
    val latestUri = helper.syncGetNormalizedUri(uri).get
    callEmbedly(latestUri)

    if (latestUri.state == NormalizedURIStates.INACTIVE) None
    else fetchedArticle match {
      case scraped: Scraped => handleSuccessfulScraped(latestUri, scraped, info, pageInfoOpt)
      case notScrapable: NotScrapable => handleNotScrapable(latestUri, notScrapable, info)
      case NotModified => handleNotModified(latestUri.url, info)
      case error: Error => handleScrapeError(latestUri, error, info)
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): ScraperResult = {
    try {
      URI.parse(normalizedUri.url) match {
        case Success(uri) =>
          uri.scheme match {
            case Some("file") => Error(-1, "forbidden scheme: %s".format("file"))
            case _ => fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
          }
        case _ => fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
      }
    } catch {
      case t: Throwable =>
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString("|")}")
        fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
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

  private def runPornDetectorIfNecessary(normalizedUri: NormalizedURI, content: String, contentLang: Lang) {
    isNonSensitive(normalizedUri.url).map { nonSensitive =>
      if (!nonSensitive) {
        if (contentLang == Lang("en") && content.size > 100) {
          val detector = new SlidingWindowPornDetector(pornDetectorFactory())
          detector.isPorn(content.take(100000)) match {
            case true if normalizedUri.restriction == None => helper.updateURIRestriction(normalizedUri.id.get, Some(Restriction.ADULT)) // don't override other restrictions
            case false if normalizedUri.restriction == Some(Restriction.ADULT) => helper.updateURIRestriction(normalizedUri.id.get, None)
            case _ =>
          }
        }
      } else {
        log.debug(s"uri $normalizedUri is exempted from sensitive check!")
      }
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): ScraperResult = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.debug(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = proxyOpt) { input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) {
            NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
          } else {
            val content = extractor.getContent
            val title = extractor.getTitle
            val canonicalUrl = extractor.getCanonicalUrl
            val alternateUrls = extractor.getAlternateUrls
            val description = extractor.getDescription
            val keywords = extractor.getKeywords
            val media = extractor.getMediaTypeString
            val signature = extractor.getSignature

            val contentLang = description match {
              case Some(desc) => LangDetector.detect(content + " " + desc)
              case None => LangDetector.detect(content)
            }
            val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language

            runPornDetectorIfNecessary(normalizedUri, content, contentLang)

            val article: Article = Article(
              id = normalizedUri.id.get,
              title = title,
              description = description,
              canonicalUrl = canonicalUrl,
              alternateUrls = alternateUrls,
              keywords = keywords,
              media = media,
              content = content,
              scrapedAt = currentDateTime,
              httpContentType = extractor.getMetadata("Content-Type"),
              httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
              state = NormalizedURIStates.SCRAPED,
              message = None,
              titleLang = Some(titleLang),
              contentLang = Some(contentLang),
              destinationUrl = fetchStatus.destinationUrl)
            val res = Scraped(article, signature, fetchStatus.redirects)
            log.debug(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
            res
          }
        case HttpStatus.SC_NOT_MODIFIED =>
          com.keepit.scraper.NotModified
        case _ =>
          Error(fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed"))
      }
    } catch {
      case e: Throwable => {
        log.error(s"[fetchArticle] fetch failed ${normalizedUri.url} $info $httpFetcher;\nException: $e; Cause: ${e.getCause};\nStack trace:\n${e.getStackTrace.mkString("|")}")
        Error(-1, "fetch failed: %s".format(e.toString))
      }
    }
  }

  // Watch out: the NormalizedURI may come back as REDIRECTED
  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): NormalizedURI = {
    redirects.dropWhile(!_.isLocatedAt(uri.url)) match {
      case Seq(redirect, _*) if !redirect.isPermanent || hasFishy301(uri) =>
        if (redirect.isPermanent) log.warn(s"Found fishy $redirect for $uri") else log.warn(s"Found non permanent $redirect for $uri")
        updateRedirectRestriction(uri, redirect)
      case permanentsRedirects =>
        HttpRedirect.resolvePermanentRedirects(uri.url, permanentsRedirects).map { absoluteDestination =>
          val validRedirect = HttpRedirect(HttpStatus.SC_MOVED_PERMANENTLY, uri.url, absoluteDestination)
          log.debug(s"Found permanent $validRedirect for $uri")
          helper.syncRecordPermanentRedirect(removeRedirectRestriction(uri), validRedirect)
        } getOrElse {
          permanentsRedirects.headOption.foreach(relative301 => log.warn(s"Ignoring relative permanent $relative301 for $uri"))
          removeRedirectRestriction(uri)
        }
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

  private def hasFishy301(movedUri: NormalizedURI): Boolean = {
    val hasFishy301Restriction = movedUri.restriction == Some(Restriction.http(301))
    lazy val isFishy = helper.syncGetLatestKeep(movedUri.url).filter(_.createdAt.isAfter(currentDateTime.minusHours(1))) match {
      case Some(recentKeep) if recentKeep.source != KeepSource.bookmarkImport => true
      case Some(importedBookmark) =>
        val parsedBookmarkUrl = URI.parse(importedBookmark.url).get.toString()
        (parsedBookmarkUrl != movedUri.url) && (httpFetcher.fetch(parsedBookmarkUrl)(httpFetcher.NO_OP).statusCode != HttpStatus.SC_MOVED_PERMANENTLY)
      case None => false
    }
    hasFishy301Restriction || isFishy
  }

  private def recordScrapedNormalization(uri: NormalizedURI, signature: Signature, canonicalUrl: String, alternateUrls: Set[String]): Unit = {
    sanitize(uri.url, canonicalUrl).foreach { properCanonicalUrl =>
      val properAlternateUrls = alternateUrls.flatMap(sanitize(uri.url, _)) - uri.url - properCanonicalUrl
      helper.syncRecordScrapedNormalization(uri.id.get, signature, properCanonicalUrl, Normalization.CANONICAL, properAlternateUrls)
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
      parsedTargetUri <- URI.safelyParse(absoluteTargetUrl)
    } yield parsedTargetUri.toString()
  }

  private def isNonSensitive(url: String): Future[Boolean] = {
    shoeboxClient.getAllURLPatterns().map { patterns =>
      val pat = patterns.find(rule => url.matches(rule.pattern))
      pat.exists(_.nonSensitive)
    }
  }
}
