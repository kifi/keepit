package com.keepit.scraper

import com.google.inject.{Provider, Inject}
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.scraper.extractor._
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.logging.Logging
import com.keepit.model._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.{HttpClient, DirectUrl, URI}
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import scala.concurrent.duration._
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Failure
import scala.util.Success
import com.keepit.common.akka.{SafeFuture, FortyTwoActor}
import play.api.Play
import akka.routing.SmallestMailboxRouter
import akka.util.Timeout
import akka.actor._
import akka.pattern.ask
import play.api.Play.current
import com.keepit.learning.porndetector.PornDetectorFactory
import com.keepit.learning.porndetector.SlidingWindowPornDetector

trait AsyncScrapeProcessor extends ScrapeProcessor

class SimpleAsyncScrapeProcessor @Inject() (asyncScraper:AsyncScraper) extends AsyncScrapeProcessor with Logging {
  def fetchBasicArticle(url:String, proxy:Option[HttpProxy], extractorType:Option[ExtractorProviderType]) = asyncScraper.asyncFetchBasicArticle(url, proxy, extractorType)
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt: Option[HttpProxy]) = asyncScraper.asyncSafeProcessURI(uri, info, proxyOpt)
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt: Option[HttpProxy]): Unit = asyncScraper.asyncSafeProcessURI(uri, info, proxyOpt)
}

class AsyncScrapeActorProcessor @Inject() (
  scraperConfig: ScraperConfig,
  sysProvider:   Provider[ActorSystem],
  procProvider:  Provider[AsyncScraperActor]
) extends AsyncScrapeProcessor with Logging {

  lazy val system = sysProvider.get
  lazy val actor = {
    val nrOfInstances = if (Play.maybeApplication.isDefined && (!Play.isTest)) Runtime.getRuntime.availableProcessors else 1
    system.actorOf(Props(procProvider.get).withRouter(SmallestMailboxRouter(nrOfInstances)))
  }

  implicit val timeout = Timeout(scraperConfig.actorTimeout)

  def fetchBasicArticle(url: String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    (actor ? FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)).mapTo[Option[BasicArticle]]
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    (actor ? ScrapeArticle(uri, info, proxyOpt)).mapTo[(NormalizedURI, Option[Article])]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Unit = {
    actor ! AsyncScrape(uri, info, proxyOpt)
  }
}


class AsyncScraperActor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  asyncScraper: AsyncScraper,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[AsyncScraperActor] created $this")

  implicit val myConfig = config

  def receive = {
    case FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt) => { // note: same as sync; remove?
      log.info(s"[async-FetchArticle] message received; url=$url")
      val ts = System.currentTimeMillis
      val extractor = extractorProviderTypeOpt match {
        case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
        case _ => extractorFactory(url)
      }
      val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
      val res = fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(asyncScraper.basicArticle(url, extractor))
        case _ => None
      }
      log.info(s"[async-FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
      sender ! res
    }

    case ScrapeArticle(uri, info, proxyOpt) => {
      log.info(s"[async-ScrapeArticle] message received; url=${uri.url}")
      val ts = System.currentTimeMillis
      asyncScraper.asyncSafeProcessURI(uri, info, proxyOpt) onComplete {
        case Success(res) =>
          log.info(s"[async-ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
          sender ! res
        case Failure(t) =>
          log.error(s"[async-ScrapeArticle] encountered exception: $t; cause: ${t.getCause}; stack: ${t.getStackTraceString}")
          sender ! (uri, None)
      }
    }

    case AsyncScrape(nuri, info, proxyOpt) => {
      log.info(s"[async-AsyncScrape] message received; url=${nuri.url}")
      val ts = System.currentTimeMillis
      asyncScraper.asyncSafeProcessURI(nuri, info, proxyOpt) onComplete {
        case Success((uri, _)) => log.info(s"[async-AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=(${uri.id}, ${uri.state})")
        case Failure(t) => {
          log.error(s"[async-AsyncScrape(${nuri.url},${nuri.id},${nuri.state}})] encountered exception: $t; cause: ${t.getCause}; stack: ${t.getStackTraceString}")
        }
      }
    }
  }
}

// ported from original (local) sync version
class AsyncScraper @Inject() (
  airbrake: AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  httpClient: HttpClient,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  pornDetectorFactory: PornDetectorFactory,
  helper: ShoeboxDbCallbackHelper
) extends Logging {

  implicit val myConfig = config

  def asyncFetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]] = {
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
      case _ => extractorFactory(url)
    }
    val isUnscrapableF = for {
      fetchStatus <- future { httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input)) }
      isUnscrapable <- helper.isUnscrapableP(url, fetchStatus.destinationUrl) if fetchStatus.statusCode == HttpStatus.SC_OK
    } yield isUnscrapable

    isUnscrapableF map { isUnscrapable =>
      if (!isUnscrapable) Some(basicArticle(url, extractor))
      else None
    }
  }

  def asyncSafeProcessURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    try {
      asyncProcessURI(uri, info, proxyOpt)
    } catch {
      case t: Throwable => {
        log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; StackTrace: ${t.getStackTraceString}")
        airbrake.notify(t)
        helper.scrapeFailed(uri, info) map { savedUri => (savedUri.getOrElse(uri), None) }
      }
    }
  }

  def asyncProcessURI(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    log.info(s"[asyncProcessURI] scraping ${uri.url} $info")
    helper.getNormalizedUri(uri) flatMap { latestUriOpt =>
      latestUriOpt match {
        case None => future { (uri, None) }
        case Some(latestUri) => latestUri.state match {
          case NormalizedURIStates.INACTIVE => future { (latestUri, None) }
          case _ => asyncProcessArticle(uri, info, latestUri, proxyOpt)
        }
      }
    }
  }

  def asyncProcessArticle(uri: NormalizedURI, info: ScrapeInfo, latestUri: NormalizedURI, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    asyncFetchArticle(uri, info, proxyOpt) flatMap { fetchResult =>
      fetchResult match {
        case Scraped(article, signature, redirects) => asyncProcessScrapedArticle(latestUri, redirects, article, signature, info, uri)
        case NotScrapable(destinationUrl, redirects) => asyncProcessUnscrapableURI(info, destinationUrl, latestUri, redirects, uri)
        case com.keepit.scraper.NotModified => asyncProcessNoChangeURI(info, uri, latestUri)
        case Error(httpStatus, msg) => asyncProcessErrorURI(latestUri, msg, info, httpStatus)
      }
    }
  }

  def asyncProcessErrorURI(latestUri: NormalizedURI, msg: String, info: ScrapeInfo, httpStatus: Int): Future[(NormalizedURI, Option[Article])] = {
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

    addToArticleStore(latestUri, article)
    helper.scrapeFailed(latestUri, info) map { savedOpt => (savedOpt.getOrElse(latestUri), None) }
  }

  def asyncProcessNoChangeURI(info: ScrapeInfo, uri: NormalizedURI, latestUri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    // update the scrape schedule, uri is not changed
    helper.saveScrapeInfo(info.withDocumentUnchanged()) map { savedInfo =>
      log.info(s"[asyncProcessURI] (${uri.url} not modified; latestUri=(${latestUri.id}, ${latestUri.state}, ${latestUri.url}})")
      (latestUri, None)
    }
  }

  def asyncProcessUnscrapableURI(info: ScrapeInfo, destinationUrl: Option[String], latestUri: NormalizedURI, redirects: Seq[HttpRedirect], uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    for {
      savedInfo <- helper.saveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
      toBeSaved <- asyncProcessRedirects(latestUri, redirects).map { redirectProcessed => redirectProcessed.withState(NormalizedURIStates.UNSCRAPABLE) }
      saved <- helper.saveNormalizedUri(toBeSaved)
    } yield (saved, None)
  }

  def asyncProcessScrapedArticle(latestUri: NormalizedURI, redirects: Seq[HttpRedirect], article: Article, signature: Signature, info: ScrapeInfo, uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {

    @inline def shouldUpdateScreenshot(uri: NormalizedURI) = {
      uri.screenshotUpdatedAt map { update =>
        Days.daysBetween(currentDateTime.withTimeAtStartOfDay, update.withTimeAtStartOfDay).getDays() >= 5
      } getOrElse true
    }

    asyncProcessRedirects(latestUri, redirects) map { updatedUri =>
      // check if document is not changed or does not need to be reindexed
      if (latestUri.title == Option(article.title) && // title change should always invoke indexing
        latestUri.restriction == updatedUri.restriction && // restriction change always invoke indexing
        latestUri.state != NormalizedURIStates.SCRAPE_FAILED &&
        signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.intervalConfig.minInterval / info.interval))
      ) {
        // the article does not need to be reindexed update the scrape schedule, uri is not changed
        helper.saveScrapeInfo(info.withDocumentUnchanged()) // todo: revisit
        log.info(s"[asyncProcessURI] (${uri.url}) no change detected")
        (latestUri, None)
      } else {
        // the article needs to be reindexed
        addToArticleStore(latestUri, article)
        val scrapedUri = updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED)
        val scrapedInfo = info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64)
        helper.scraped(scrapedUri, scrapedInfo)
        // Report canonical url
        article.canonicalUrl.foreach(recordCanonicalUrl(latestUri, signature, _, article.alternateUrls)) // todo: make part of "scraped" call
        if (shouldUpdateScreenshot(scrapedUri)) {
          s3ScreenshotStore.updatePicture(scrapedUri) onComplete { tr =>
            tr match {
              case Success(res) => log.info(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] added image to screenshot store. Result: $res")
              case Failure(e) => log.error(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] failed to add image to screenshot store. Exception: $e; StackTrace: ${e.getStackTraceString}") // anything else?
            }
          }
        }
        log.info(s"[asyncProcessURI] (${uri.url}) needs re-indexing; scrapedUri=(${scrapedUri.id}, ${scrapedUri.state}, ${scrapedUri.url})")
        (scrapedUri, Some(article))
      }
    }
  }


  def addToArticleStore(latestUri: NormalizedURI, article: Article):Future[ArticleStore] = {
    future {
      articleStore += (latestUri.id.get -> article)
    } andThen {
      case Success(store) =>
        log.info(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] added article(${article.title.take(20)} to S3")
        store
      case Failure(e) =>
        log.error(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] failed to add article(${article.title.take(20)} to S3. Exception: $e; StackTrace: ${e.getStackTraceString}") // anything else?
        e
    }
  }

  def asyncFetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[ScraperResult] = {
    URI.parse(normalizedUri.url) match {
      case Success(uri) =>
        uri.scheme match {
          case Some("file") => future { Error(-1, "forbidden scheme: %s".format("file")) }
          case _ => asyncFetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
        }
      case _ => asyncFetchArticle(normalizedUri, httpFetcher, info, proxyOpt)
    }
  }

  def getIfModifiedSince(uri: NormalizedURI, info: ScrapeInfo) = {
    if (uri.state == NormalizedURIStates.SCRAPED) {
      info.signature match {
        case "" => None // no signature. this is the first time
        case _ => Some(info.lastScrape)
      }
    } else {
      None
    }
  }

  def asyncFetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[ScraperResult] = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.info(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    future {
      httpFetcher.fetch(url, ifModifiedSince, proxy = proxyOpt){ input => extractor.process(input) }
    } flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          helper.isUnscrapableP(url, fetchStatus.destinationUrl) map { isUnscrapable =>
            if (isUnscrapable) {
              NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
            } else {
              val content = extractor.getContent
              val title = getTitle(extractor)
              val description = getDescription(extractor)
              val canonicalUrl = getCanonicalUrl(extractor)
              val alternateUrls = getAlternateUrls(extractor)
              val keywords = getKeywords(extractor)
              val media = getMediaTypeString(extractor)
              val signature = Signature(Seq(title, description.getOrElse(""), keywords.getOrElse(""), content))
              val contentLang = description match {
                case Some(desc) => LangDetector.detect(content + " " + desc)
                case None => LangDetector.detect(content)
              }
              val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language

              val detector = new SlidingWindowPornDetector(pornDetectorFactory())
              if (true || detector.isPorn(title + " " + content.take(10000) + " " + description) && normalizedUri.restriction != Some(Restriction.ADULT)){
                println("\n\n==================\n\n updating uri restriction")
                helper.updateURIRestriction(normalizedUri.id.get, Some(Restriction.ADULT))
              }

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
              log.info(s"[fetchArticle] result=(Scraped(dstUrl=${fetchStatus.destinationUrl} redirects=${fetchStatus.redirects}) article=(${article.id}, ${article.title}, content.len=${article.content.length}})")
              Scraped(article, signature, fetchStatus.redirects)
            }
          }
        case HttpStatus.SC_NOT_MODIFIED => future { com.keepit.scraper.NotModified }
        case _ => future { Error(fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed")) }
      }
    }
  }

  private[this] def getTitle(x: Extractor): String = x.getMetadata("title").getOrElse("")

  private[this] def getCanonicalUrl(x: Extractor): Option[String] = x.getCanonicalUrl()

  private[this] def getAlternateUrls(x: Extractor): Set[String] = x.getAlternateUrls()

  private[this] def getDescription(x: Extractor): Option[String] = x.getMetadata("description")

  private[this] def getKeywords(x: Extractor): Option[String] = x.getKeywords

  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)

  def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    description = getDescription(extractor),
    canonicalUrl = getCanonicalUrl(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  def asyncProcessRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): Future[NormalizedURI] = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => SafeFuture {
        if (redirect.isPermanent) log.warn(s"Found fishy 301 $redirect for $uri")
        updateRedirectRestriction(uri, redirect)
      }
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => helper.recordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
      case _ => future { removeRedirectRestriction(uri) }
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

  private def hasFishy301(movedUri: NormalizedURI) = Await.result(asyncHasFishy301(movedUri), 5 seconds)
  private def asyncHasFishy301(movedUri: NormalizedURI): Future[Boolean] = {
    val hasFishy301Restriction = movedUri.restriction == Some(Restriction.http(301))
    lazy val isFishy = helper.getLatestBookmark(movedUri.id.get).map { latestKeepOption =>
      latestKeepOption.filter(_.updatedAt.isAfter(currentDateTime.minusHours(1))) match {
        case Some(recentKeep) if recentKeep.source != BookmarkSource.bookmarkImport => true
        case Some(importedBookmark) => (importedBookmark.url != movedUri.url) && (httpFetcher.fetch(importedBookmark.url)(httpFetcher.NO_OP).statusCode != HttpStatus.SC_MOVED_PERMANENTLY)
        case None => false
      }
    }

    if (hasFishy301Restriction) Future.successful(true) else isFishy
  }

  private def recordCanonicalUrl(uri: NormalizedURI, signature: Signature, canonicalUrl: String, alternateUrls: Set[String]): Unit = {
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

}
