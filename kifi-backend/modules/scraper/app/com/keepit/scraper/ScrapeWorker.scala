package com.keepit.scraper

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.concurrent.ExecutionContext
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
import com.keepit.search.Lang
import com.keepit.shoebox.{ ShoeboxScraperClient, ShoeboxServiceClient }
import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.scraper.embedly.EmbedlyCommander

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
    syncHelper: SyncShoeboxDbCallbacks,
    dbHelper: ShoeboxDbCallbacks,
    shoeboxScraperClient: ShoeboxScraperClient,
    shoeboxClient: ShoeboxServiceClient,
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
          dbHelper.saveScrapeInfo(info.withFailure()) map { _ => None }
        }
    }
  }

  private def recordScrapeFailure(uri: NormalizedURI): Future[Unit] = {
    dbHelper.getNormalizedUri(uri).flatMap { latestUriOpt =>
      latestUriOpt match {
        case None => Future.successful[Unit]()
        case Some(latestUri) =>
          if (latestUri.state != NormalizedURIStates.INACTIVE && latestUri.state != NormalizedURIStates.REDIRECTED) {
            dbHelper.updateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
          } else Future.successful[Unit]()
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
      syncHelper.syncSaveScrapeInfo(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
      None
    } else if (!needReIndex(latestUri, updatedUri, article, signature, info)) {
      syncHelper.syncSaveScrapeInfo(info.withDocumentUnchanged())
      log.debug(s"[processURI] (${latestUri.url}) no change detected")
      None
    } else {

      articleStore += (latestUri.id.get -> article)
      updateWordCountCache(latestUri.id.get, Some(article))

      val scrapedURI = syncHelper.syncSaveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))

      // scrape schedule
      syncHelper.syncSaveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))

      syncHelper.syncGetBookmarksByUriWithoutTitle(scrapedURI.id.get).foreach { bookmark =>
        syncHelper.syncSaveBookmark(bookmark.copy(title = scrapedURI.title))
      }

      // Report canonical url
      article.canonicalUrl.foreach(recordScrapedNormalization(latestUri, signature, _, article.alternateUrls))

      log.debug(s"[processURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

      if (shouldUpdateScreenshot(scrapedURI)) {
        scrapedURI.id map shoeboxScraperClient.updateScreenshots
      }

      if (shouldUpdateImage(latestUri, scrapedURI, pageInfoOpt)) {
        scrapedURI.id map { id =>
          shoeboxScraperClient.getUriImage(id) map { res =>
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
      syncHelper.syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
      val updatedUri = processRedirects(latestUri, redirects)
      if (updatedUri.state == NormalizedURIStates.REDIRECTED || updatedUri.normalization == Some(Normalization.MOVED)) updatedUri
      else {
        syncHelper.syncUpdateNormalizedURIState(updatedUri.id.get, NormalizedURIStates.UNSCRAPABLE)
        updatedUri.withState(NormalizedURIStates.UNSCRAPABLE)
      }
    }
    log.debug(s"[processURI] (${latestUri.url}) not scrapable; unscrapableURI=(${unscrapableURI.id}, ${unscrapableURI.state}, ${unscrapableURI.url}})")

    updateWordCountCache(unscrapableURI.id.get, None)

    None
  }

  private def handleNotModified(url: String, info: ScrapeInfo): Option[Article] = {
    // update the scrape schedule, uri is not changed
    val updatedInfo = syncHelper.syncSaveScrapeInfo(info.withDocumentUnchanged())
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
    syncHelper.syncSaveScrapeInfo(info.withFailure())
    syncHelper.syncUpdateNormalizedURIState(latestUri.id.get, NormalizedURIStates.SCRAPE_FAILED)
    log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${latestUri.id}, ${latestUri.state}, ${latestUri.url})")
    updateWordCountCache(latestUri.id.get, None)
    None
  }

  private def callEmbedly(uri: NormalizedURI): Unit = {
    embedlyCommander.fetchEmbedlyInfo(uri.id.get, uri.url)
  }

  private def process(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]): Future[Option[Article]] = {
    for {
      fetchedArticle <- safeFetch(uri, info, proxyOpt)
      uriOpt <- dbHelper.getNormalizedUri(uri)
    } yield {
      val articleOpt = uriOpt flatMap { latestUri =>
        callEmbedly(latestUri) // @martin: do we need to do this for every scrape?

        if (latestUri.state == NormalizedURIStates.INACTIVE) None
        else fetchedArticle match { // all blocking -- next on the list
          case scraped: Scraped => handleSuccessfulScraped(latestUri, scraped, info, pageInfoOpt)
          case notScrapable: NotScrapable => handleNotScrapable(latestUri, notScrapable, info)
          case NotModified => handleNotModified(latestUri.url, info)
          case error: Error => handleScrapeError(latestUri, error, info)
        }
      }
      articleOpt
    }
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

  private def runPornDetectorIfNecessary(normalizedUri: NormalizedURI, content: String, contentLang: Lang) {
    isNonSensitive(normalizedUri.url).map { nonSensitive =>
      if (!nonSensitive) {
        if (contentLang == Lang("en") && content.size > 100) {
          val detector = new SlidingWindowPornDetector(pornDetectorFactory())
          detector.isPorn(content.take(100000)) match {
            case true if normalizedUri.restriction == None => syncHelper.updateURIRestriction(normalizedUri.id.get, Some(Restriction.ADULT)) // don't override other restrictions
            case false if normalizedUri.restriction == Some(Restriction.ADULT) => syncHelper.updateURIRestriction(normalizedUri.id.get, None)
            case _ =>
          }
        }
      } else {
        log.debug(s"uri $normalizedUri is exempted from sensitive check!")
      }
    }
  }

  private def fetch(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Future[ScraperResult] = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.debug(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    httpFetcher.get(url, ifModifiedSince, proxy = proxyOpt) { input => extractor.process(input) } flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          dbHelper.isUnscrapableP(url, fetchStatus.destinationUrl) map { unscrapable =>
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

              runPornDetectorIfNecessary(normalizedUri, content, contentLang)

              val article: Article = Article(
                id = normalizedUri.id.get,
                title = title,
                description = description,
                canonicalUrl = extractor.getCanonicalUrl,
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
              val res = Scraped(article, extractor.getSignature, fetchStatus.redirects)
              log.debug(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
              res
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
  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): NormalizedURI = {
    redirects.dropWhile(!_.isLocatedAt(uri.url)) match {
      case Seq(redirect, _*) if !redirect.isPermanent || hasFishy301(uri) =>
        if (redirect.isPermanent) log.warn(s"Found fishy $redirect for $uri") else log.warn(s"Found non permanent $redirect for $uri")
        updateRedirectRestriction(uri, redirect)
      case permanentsRedirects =>
        HttpRedirect.resolvePermanentRedirects(uri.url, permanentsRedirects).map { absoluteDestination =>
          val validRedirect = HttpRedirect(HttpStatus.SC_MOVED_PERMANENTLY, uri.url, absoluteDestination)
          log.debug(s"Found permanent $validRedirect for $uri")
          syncHelper.syncRecordPermanentRedirect(removeRedirectRestriction(uri), validRedirect)
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
    lazy val isFishy = syncHelper.syncGetLatestKeep(movedUri.url).filter(_.createdAt.isAfter(currentDateTime.minusHours(1))) match {
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
      syncHelper.syncRecordScrapedNormalization(uri.id.get, signature, properCanonicalUrl, Normalization.CANONICAL, properAlternateUrls)
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
    shoeboxScraperClient.getAllURLPatterns().map { patterns =>
      val pat = patterns.find(rule => url.matches(rule.pattern))
      pat.exists(_.nonSensitive)
    }
  }
}
