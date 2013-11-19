package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.extractor.{Extractor, ExtractorFactory}
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging
import com.keepit.model._
import java.io.File
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.common.db.Id
import scala.util.{Failure, Try, Success}
import scala.concurrent.duration._
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class AsyncScrapeProcessorPlugin @Inject() (asyncScraper:AsyncScraper) extends ScrapeProcessorPlugin with Logging {

  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy]): Future[Option[BasicArticle]] = {
    asyncScraper.asyncFetchBasicArticle(url, proxyOpt)
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = {
    asyncScraper.asyncProcessURI(uri, info)
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo): Unit =  { // todo: consolidate
    asyncScraper.asyncProcessURI(uri, info)
  }

}

// ported from original (local) sync version
class AsyncScraper @Inject() (
  airbrake: AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  shoeboxServiceClient: ShoeboxServiceClient
) extends Logging {

  implicit val myConfig = config

  def asyncFetchBasicArticle(url:String, proxyOpt:Option[HttpProxy]):Future[Option[BasicArticle]] = {
    val extractor = extractorFactory(url)
    val isUnscrapableF = for {
      fetchStatus <- future { httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input)) }
      isUnscrapable <- asyncIsUnscrapableP(url, fetchStatus.destinationUrl) if fetchStatus.statusCode == HttpStatus.SC_OK
    } yield isUnscrapable

    isUnscrapableF map { isUnscrapable =>
      if (!isUnscrapable) Some(basicArticle(url, extractor))
      else None
    }
  }

  // def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = Await.result(asyncSafeProcessURI(uri, info), 5 seconds)
  def asyncSafeProcessURI(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = {
    try {
      asyncProcessURI(uri, info)
    } catch {
      case t: Throwable => { // todo: revisit
        log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
        airbrake.notify(t)
        getNormalizedUri(uri) flatMap { latestUriOpt =>
          // update the uri state to SCRAPE_FAILED
          val savedUriF = latestUriOpt match {
            case None => future { uri }
            case Some(latestUri) =>
              if (latestUri.state == NormalizedURIStates.INACTIVE) future { latestUri } else {
                saveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
              }
          }

          val res:Future[(NormalizedURI, Option[Article])] = savedUriF map { savedUri =>
            // then update the scrape schedule
            saveScrapeInfo(info.withFailure()) // todo: revisit
            (savedUri, None)
          }
          res
        }
      }
    }
  }

  // def processURI(uri: NormalizedURI, info: ScrapeInfo) = Await.result(asyncProcessURI(uri, info), 5 seconds)
  def asyncProcessURI(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = {
    log.info(s"[asyncProcessURI] scraping ${uri.url} $info")
    getNormalizedUri(uri) flatMap { latestUriOpt =>
      latestUriOpt match {
        case None => future { (uri, None) }
        case Some(latestUri) => latestUri.state match {
          case NormalizedURIStates.INACTIVE => future { (latestUri, None) }
          case _ => asyncProcessArticle(uri, info, latestUri)
        }
      }
    }
  }

  def asyncProcessArticle(uri: NormalizedURI, info: ScrapeInfo, latestUri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    asyncFetchArticle(uri, info) flatMap { fetchResult =>
      fetchResult match {
        case Scraped(article, signature, redirects) => asyncProcessScrapedArticle(latestUri, redirects, article, signature, info, uri)
        case NotScrapable(destinationUrl, redirects) => asyncProcessUnscrapableURI(info, destinationUrl, latestUri, redirects, uri)
        case com.keepit.scraper.NotModified => asyncProcessNoChangeURI(info, uri, latestUri)
        case Error(httpStatus, msg) => asyncProcessErrorURI(latestUri, msg, info, httpStatus)
      }
    }
  }

  def processErrorURI(latestUri: NormalizedURI, msg: String, info: ScrapeInfo, httpStatus: Int): (NormalizedURI, Option[Article]) = Await.result(asyncProcessErrorURI(latestUri, msg, info, httpStatus), 5 seconds)
  def asyncProcessErrorURI(latestUri: NormalizedURI, msg: String, info: ScrapeInfo, httpStatus: Int): Future[(NormalizedURI, Option[Article])] = {
    // store a fallback article in a store map
    val article = Article(
      id = latestUri.id.get,
      title = latestUri.title.getOrElse(""),
      description = None,
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
    for {
      updatedStore <- addToArticleStore(latestUri, article)
      savedInfo <- saveScrapeInfo(info.withFailure())
      savedURI <- saveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED)) // todo: revisit
    } yield (savedURI, None)
  }

//  def processNoChangeURI(info: ScrapeInfo, uri: NormalizedURI, latestUri: NormalizedURI) = Await.result(asyncProcessNoChangeURI(info, uri, latestUri), 5 seconds)
  def asyncProcessNoChangeURI(info: ScrapeInfo, uri: NormalizedURI, latestUri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    // update the scrape schedule, uri is not changed
    saveScrapeInfo(info.withDocumentUnchanged()) map { savedInfo =>
      log.info(s"[asyncProcessURI] (${uri.url} not modified; latestUri=(${latestUri.id}, ${latestUri.state}, ${latestUri.url}})")
      (latestUri, None)
    }
  }

//  def processUnscrapableURI(info: ScrapeInfo, destinationUrl: Option[String], latestUri: NormalizedURI, redirects: Seq[HttpRedirect], uri: NormalizedURI) = Await.result(asyncProcessUnscrapableURI(info, destinationUrl, latestUri, redirects, uri), 5 seconds)
  def asyncProcessUnscrapableURI(info: ScrapeInfo, destinationUrl: Option[String], latestUri: NormalizedURI, redirects: Seq[HttpRedirect], uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    for {
      savedInfo <- saveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
      toBeSaved <- asyncProcessRedirects(latestUri, redirects).map { redirectProcessed => redirectProcessed.withState(NormalizedURIStates.UNSCRAPABLE) }
      saved <- saveNormalizedUri(toBeSaved)
    } yield (saved, None)
  }

//  def processScrapedArticle(latestUri: NormalizedURI, redirects: Seq[HttpRedirect], article: Article, signature: Signature, info: ScrapeInfo, uri: NormalizedURI) = Await.result(asyncProcessScrapedArticle(latestUri, redirects, article, signature, info, uri), 5 seconds)
  def asyncProcessScrapedArticle(latestUri: NormalizedURI, redirects: Seq[HttpRedirect], article: Article, signature: Signature, info: ScrapeInfo, uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {

    @inline def shouldUpdateScreenshot(uri: NormalizedURI) = {
      uri.screenshotUpdatedAt map { update =>
        Days.daysBetween(currentDateTime.toDateMidnight, update.toDateMidnight).getDays() >= 5
      } getOrElse true
    }

    asyncProcessRedirects(latestUri, redirects) flatMap { updatedUri =>
      // check if document is not changed or does not need to be reindexed
      if (latestUri.title == Option(article.title) && // title change should always invoke indexing
        latestUri.restriction == updatedUri.restriction && // restriction change always invoke indexing
        latestUri.state != NormalizedURIStates.SCRAPE_WANTED &&
        latestUri.state != NormalizedURIStates.SCRAPE_FAILED &&
        signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.minInterval / info.interval))
      ) {
        // the article does not need to be reindexed update the scrape schedule, uri is not changed
        saveScrapeInfo(info.withDocumentUnchanged()) // todo: revisit
        log.info(s"[asyncProcessURI] (${uri.url}) no change detected")
        future { (latestUri, None) }
      } else {

        // the article needs to be reindexed
        addToArticleStore(latestUri, article)

        // first update the uri state to SCRAPED
        saveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED)) map { scrapedURI =>
          // then update the scrape schedule
          saveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64)) // todo: revisit
          getBookmarksByUriWithoutTitle(scrapedURI.id.get) map { bookmarks =>
            bookmarks.foreach { bookmark =>
              saveBookmark(bookmark.copy(title = scrapedURI.title)) // todo: revisit
            }
          }
          log.info(s"[asyncProcessURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

          if (shouldUpdateScreenshot(scrapedURI)) {
            s3ScreenshotStore.updatePicture(scrapedURI) onComplete { tr =>
              tr match {
                case Success(res) => log.info(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] added image to screenshot store. Result: $res")
                case Failure(e) => log.error(s"[asyncProcessURI(${latestUri.id},${latestUri.url}})] failed to add image to screenshot store. Exception: $e; StackTrace: ${e.getStackTraceString}") // anything else?
              }
            }
          }

          log.info(s"[asyncProcessURI] (${uri.url}) needs re-indexing; scrapedURI=(${scrapedURI.id}, ${scrapedURI.state}, ${scrapedURI.url})")
          (scrapedURI, Some(article))
        }
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

//  def fetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo) = Await.result(asyncFetchArticle(normalizedUri, info), 5 seconds)

  def asyncFetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo): Future[ScraperResult] = {
    URI.parse(normalizedUri.url) match {
      case Success(uri) =>
        uri.scheme match {
          case Some("file") => future { Error(-1, "forbidden scheme: %s".format("file")) }
          case _ => asyncFetchArticle(normalizedUri, httpFetcher, info)
        }
      case _ => asyncFetchArticle(normalizedUri, httpFetcher, info)
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

//  def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo) = Await.result(asyncFetchArticle(normalizedUri, httpFetcher, info), 5 seconds)

  def asyncFetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo): Future[ScraperResult] = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.info(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    getProxyP(url) map { proxyOpt => // todo: remove this
      httpFetcher.fetch(url, ifModifiedSince, proxy = proxyOpt){ input => extractor.process(input) }
    } flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          asyncIsUnscrapableP(url, fetchStatus.destinationUrl) map { isUnscrapable =>
            if (isUnscrapable) {
              NotScrapable(fetchStatus.destinationUrl, fetchStatus.redirects)
            } else {
              val content = extractor.getContent
              val title = getTitle(extractor)
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

  private[this] def getDescription(x: Extractor): Option[String] = x.getMetadata("description")

  private[this] def getKeywords(x: Extractor): Option[String] = x.getKeywords

  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)

  def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    description = getDescription(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  // def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]) = Await.result(asyncProcessRedirects(uri, redirects), 5 seconds)

  def asyncProcessRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): Future[NormalizedURI] = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => future { updateRedirectRestriction(uri, redirect) }
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => recordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
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
    getLatestBookmark(movedUri.id.get).map { bookmarkOpt =>
      val wasKeptRecently = bookmarkOpt map {
        _.updatedAt.isAfter(currentDateTime.minusHours(1))
      } getOrElse(false)
      hasFishy301Restriction || wasKeptRecently
    }
  }

  // db helpers

  private[scraper] def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }

  // private[scraper] def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI] = Await.result(getNormalizedUri(uri), 5 seconds)

  private[scraper] def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI] = shoeboxServiceClient.saveNormalizedURI(uri)

  // private[scraper] def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI = Await.result(saveNormalizedUri(uri), 5 seconds)

  private[scraper] def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo] = shoeboxServiceClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE))

  // private[scraper] def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = Await.result(saveScrapeInfo(info), 5 seconds)

  private[scraper] def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)

  // private[scraper] def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark] = Await.result(getBookmarksByUriWithoutTitle(uriId), 5 seconds)

  private[scraper] def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = shoeboxServiceClient.getLatestBookmark(uriId)

  // private[scraper] def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark] = Await.result(getLatestBookmark(uriId), 5 seconds)

  private[scraper] def saveBookmark(bookmark:Bookmark): Future[Bookmark] = shoeboxServiceClient.saveBookmark(bookmark)

  private[scraper] def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)

  // private[scraper] def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = Await.result(recordPermanentRedirect(uri, redirect), 5 seconds)

  private[scraper] def getProxyP(url: String):Future[Option[HttpProxy]] = shoeboxServiceClient.getProxyP(url)

  // private[scraper] def syncGetProxyP(url: String):Option[HttpProxy] = Await.result(getProxyP(url), 5 seconds)

  private[scraper] def asyncIsUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean] = shoeboxServiceClient.isUnscrapableP(url, destinationUrl)

  // def isUnscrapableP(url: String, destinationUrl: Option[String]) = Await.result(asyncIsUnscrapableP(url, destinationUrl), 5 seconds)


}