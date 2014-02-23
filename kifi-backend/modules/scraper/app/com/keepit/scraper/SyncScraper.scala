package com.keepit.scraper

import com.google.inject._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import java.io.File
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.{DirectUrl, HttpClient, URI}
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import scala.util.Success

// straight port from original (local) code
class SyncScraper @Inject() (
  airbrake: AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  httpClient: HttpClient,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends Logging {

  implicit val myConfig = config
  val awaitTTL = (myConfig.syncAwaitTimeout seconds)

  private[scraper] def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): (NormalizedURI, Option[Article]) = try {
    processURI(uri, info, proxyOpt)
  } catch {
    case t: Throwable => {
      log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString("|")}")
      airbrake.notify(t)
      val latestUriOpt = helper.syncGetNormalizedUri(uri)
      // update the uri state to SCRAPE_FAILED
      val savedUriOpt = for (latestUri <- latestUriOpt) yield {
        if (latestUri.state == NormalizedURIStates.INACTIVE) latestUri else {
          helper.syncSaveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
      }
      // then update the scrape schedule
      val savedInfoF = helper.syncSaveScrapeInfo(info.withFailure())
      (savedUriOpt.getOrElse(uri), None)
    }
  }

  private def processURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): (NormalizedURI, Option[Article]) = {
    log.info(s"[processURI] scraping ${uri.url} $info")
    val fetchedArticle = fetchArticle(uri, info, proxyOpt)
    val latestUri = helper.syncGetNormalizedUri(uri).get
    if (latestUri.state == NormalizedURIStates.INACTIVE) (latestUri, None)
    else fetchedArticle match {
      case Scraped(article, signature, redirects) =>
        val updatedUri = processRedirects(latestUri, redirects)

        // check if document is not changed or does not need to be reindexed
        if (latestUri.title == Option(article.title) && // title change should always invoke indexing
          latestUri.restriction == updatedUri.restriction && // restriction change always invoke indexing
          latestUri.state != NormalizedURIStates.SCRAPE_WANTED &&
          latestUri.state != NormalizedURIStates.SCRAPE_FAILED &&
          signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.intervalConfig.minInterval / info.interval))
        ) {
          // the article does not need to be reindexed update the scrape schedule, uri is not changed
          helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
          log.info(s"[processURI] (${uri.url}) no change detected")
          (latestUri, None)
        } else {
          // the article needs to be reindexed

          // store a scraped article in a store map
          articleStore += (latestUri.id.get -> article)

          // first update the uri state to SCRAPED
          val scrapedURI = helper.syncSaveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))

          // then update the scrape schedule
          helper.syncSaveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
          helper.syncGetBookmarksByUriWithoutTitle(scrapedURI.id.get).foreach { bookmark =>
            helper.syncSaveBookmark(bookmark.copy(title = scrapedURI.title))
          }

          // Report canonical url
          article.canonicalUrl.foreach(recordCanonicalUrl(latestUri, signature, _))

          log.info(s"[processURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

          def shouldUpdateScreenshot(uri: NormalizedURI) = {
            uri.screenshotUpdatedAt map { update =>
              Days.daysBetween(currentDateTime.withTimeAtStartOfDay, update.withTimeAtStartOfDay).getDays() >= 5
            } getOrElse true
          }
          if(shouldUpdateScreenshot(scrapedURI)) s3ScreenshotStore.updatePicture(scrapedURI)

          log.info(s"[processURI] (${uri.url}) needs re-indexing; scrapedURI=(${scrapedURI.id}, ${scrapedURI.state}, ${scrapedURI.url})")
          (scrapedURI, Some(article))
        }
      case NotScrapable(destinationUrl, redirects) =>
        val unscrapableURI = {
          helper.syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
          val toBeSaved = processRedirects(latestUri, redirects).withState(NormalizedURIStates.UNSCRAPABLE)
          helper.syncSaveNormalizedUri(toBeSaved)
        }
        log.info(s"[processURI] (${uri.url}) not scrapable; unscrapableURI=(${unscrapableURI.id}, ${unscrapableURI.state}, ${unscrapableURI.url}})")
        (unscrapableURI, None)
      case com.keepit.scraper.NotModified =>
        // update the scrape schedule, uri is not changed
        helper.syncSaveScrapeInfo(info.withDocumentUnchanged())
        log.info(s"[processURI] (${uri.url} not modified; latestUri=(${latestUri.id}, ${latestUri.state}, ${latestUri.url}})")
        (latestUri, None)
      case Error(httpStatus, msg) =>
        // store a fallback article in a store map
        val article = Article(
          id = latestUri.id.get,
          title = latestUri.title.getOrElse(""),
          description = None,
          canonicalUrl = None,
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
        val errorURI = {
          helper.syncSaveScrapeInfo(info.withFailure())
          helper.syncSaveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
        log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${errorURI.id}, ${errorURI.state}, ${errorURI.url})")
        (errorURI, None)
    }
  }

  def fetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): ScraperResult = {
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
      case t: Throwable => {
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString("|")}")
        fetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
      }
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

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): ScraperResult = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.info(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = proxyOpt){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) {
            NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
          } else {
            val content = extractor.getContent
            val title = getTitle(extractor)
            val canonicalUrl = getCanonicalUrl(extractor)
            val description = getDescription(extractor)
            val keywords = getKeywords(extractor)
            val media = getMediaTypeString(extractor)
            val signature = Signature(Seq(title, description.getOrElse(""), keywords.getOrElse(""), content))

            val contentLang = description match {
              case Some(desc) => LangDetector.detect(content + " " + desc)
              case None => LangDetector.detect(content)
            }
            val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language

            val article: Article = Article(
              id = normalizedUri.id.get,
              title = title,
              description = description,
              canonicalUrl = canonicalUrl,
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
            log.info(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
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

  private[this] def getTitle(x: Extractor): String = x.getMetadata("title").getOrElse("")

  private[this] def getCanonicalUrl(x: Extractor): Option[String] = x.getCanonicalUrl()

  private[this] def getDescription(x: Extractor): Option[String] = x.getMetadata("description")

  private[this] def getKeywords(x: Extractor): Option[String] = x.getKeywords

  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)

  def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    canonicalUrl = getCanonicalUrl(extractor),
    description = getDescription(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): NormalizedURI = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => {
        if (redirect.isPermanent) log.warn(s"Found fishy 301 $redirect for $uri")
        updateRedirectRestriction(uri, redirect)
      }
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => helper.syncRecordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
      case _ => removeRedirectRestriction(uri)
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
    lazy val isFishy = helper.syncGetLatestBookmark(movedUri.id.get).filter(_.updatedAt.isAfter(currentDateTime.minusHours(1))) match {
      case Some(recentKeep) if recentKeep.source != BookmarkSource.bookmarkImport => true
      case Some(importedBookmark) => (importedBookmark.url != movedUri.url) && (httpFetcher.fetch(importedBookmark.url)(httpFetcher.NO_OP).statusCode != HttpStatus.SC_MOVED_PERMANENTLY)
      case None => false
    }
    hasFishy301Restriction || isFishy
  }

  private def recordCanonicalUrl(uri: NormalizedURI, signature: Signature, canonicalUrl: String): Unit = {
    sanitize(uri.url, canonicalUrl).foreach { properCanonicalUrl =>
      helper.syncRecordScrapedNormalization(uri.id.get, signature, properCanonicalUrl, Normalization.CANONICAL)
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
}
