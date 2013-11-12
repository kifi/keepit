package com.keepit.scraper

import com.google.inject.{Provider, Inject, ImplementedBy}
import play.api.Plugin
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.scraper.extractor.{Extractor, ExtractorFactory}
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.shoebox.ShoeboxServiceClient
import java.io.File
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.common.db.Id
import scala.Some
import scala.util.Success
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@ImplementedBy(classOf[ScrapeProcessorPluginImpl])
trait ScrapeProcessorPlugin extends Plugin {
  def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy]):Future[Option[BasicArticle]]
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo):Future[(NormalizedURI, Option[Article])]
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo): Unit
}

class ScrapeProcessorPluginImpl @Inject() (sysProvider: Provider[ActorSystem], procProvider: Provider[ScrapeProcessor]) extends ScrapeProcessorPlugin with Logging {

  lazy val system = sysProvider.get
  def actor = system.actorOf(Props { procProvider.get })

  implicit val timeout = Timeout(5 seconds)

  def fetchBasicArticle(url: String, proxyOpt:Option[HttpProxy]): Future[Option[BasicArticle]] = {
    (actor ? FetchBasicArticle(url, proxyOpt)).mapTo[Option[BasicArticle]]
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = {
    (actor ? ScrapeArticle(uri, info)).mapTo[(NormalizedURI, Option[Article])]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo): Unit = {
    actor ! AsyncScrape(uri, info)
  }
}

case class AsyncScrape(uri:NormalizedURI, info:ScrapeInfo)
case class FetchBasicArticle(url:String, proxyOpt:Option[HttpProxy])
case class ScrapeArticle(uri:NormalizedURI, info:ScrapeInfo)

class ScrapeProcessor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  scraperConfig: ScraperConfig,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  shoeboxServiceClient: ShoeboxServiceClient
) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScraperProcessor-actor] created $this")

  implicit val myConfig = scraperConfig

  def receive = {

    case FetchBasicArticle(url, proxyOpt) => {
      log.info(s"[FetchArticle] message received; url=$url")
      val ts = System.currentTimeMillis
      val extractor = extractorFactory(url)
      val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
      val res = fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(isUnscrapableP(url, fetchStatus.destinationUrl)) => Some(basicArticle(url, extractor))
        case _ => None
      }
      log.info(s"[FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
      sender ! res
    }

    case ScrapeArticle(uri, info) => {
      log.info(s"[ScrapeArticle] message received; url=${uri.url}")
      val ts = System.currentTimeMillis
      val res = safeProcessURI(uri, info)
      log.info(s"[ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
      sender ! res
    }
    case AsyncScrape(nuri, info) => {
      log.info(s"[AsyncScrape] message received; url=${nuri.url}")
      val ts = System.currentTimeMillis
      val (uri, a) = safeProcessURI(nuri, info)
      log.info(s"[AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=(${uri.id}, ${uri.state})")
    }
  }

  private[scraper] def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = try {
    processURI(uri, info)
  } catch {
    case t: Throwable => {
      log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
      airbrake.notify(t)
      val latestUriOpt = syncGetNormalizedUri(uri)
      // update the uri state to SCRAPE_FAILED
      val savedUriOpt = for (latestUri <- latestUriOpt) yield {
        if (latestUri.state == NormalizedURIStates.INACTIVE) latestUri else {
          Await.result(saveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED)), 5 seconds)
        }
      }
      // then update the scrape schedule
      val savedInfoF = saveScrapeInfo(info.withFailure())
      (savedUriOpt.getOrElse(uri), None)
    }
  }

  private def processURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = {
    log.info(s"[processURI] scraping ${uri.url} $info")
    val fetchedArticle = fetchArticle(uri, info)
    val latestUri = syncGetNormalizedUri(uri).get
    if (latestUri.state == NormalizedURIStates.INACTIVE) (latestUri, None)
    else fetchedArticle match {
      case Scraped(article, signature, redirects) =>
        val updatedUri = processRedirects(latestUri, redirects)

        // check if document is not changed or does not need to be reindexed
        if (latestUri.title == Option(article.title) && // title change should always invoke indexing
          latestUri.restriction == updatedUri.restriction && // restriction change always invoke indexing
          latestUri.state != NormalizedURIStates.SCRAPE_WANTED &&
          latestUri.state != NormalizedURIStates.SCRAPE_FAILED &&
          signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.minInterval / info.interval))
        ) {
          // the article does not need to be reindexed update the scrape schedule, uri is not changed
          saveScrapeInfo(info.withDocumentUnchanged())
          log.info(s"[processURI] (${uri.url}) no change detected")
          (latestUri, None)
        } else {
          // the article needs to be reindexed

          // store a scraped article in a store map
          articleStore += (latestUri.id.get -> article)

          // first update the uri state to SCRAPED
          val scrapedURI = syncSaveNormalizedUri(updatedUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))

          // then update the scrape schedule
          saveScrapeInfo(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
          syncGetBookmarksByUriWithoutTitle(scrapedURI.id.get).foreach { bookmark =>
            saveBookmark(bookmark.copy(title = scrapedURI.title))
          }
          log.info(s"[processURI] fetched uri ${scrapedURI.url} => article(${article.id}, ${article.title})")

          def shouldUpdateScreenshot(uri: NormalizedURI) = {
            uri.screenshotUpdatedAt map { update =>
              Days.daysBetween(currentDateTime.toDateMidnight, update.toDateMidnight).getDays() >= 5
            } getOrElse true
          }
          if(shouldUpdateScreenshot(scrapedURI)) s3ScreenshotStore.updatePicture(scrapedURI)

          log.info(s"[processURI] (${uri.url}) needs re-indexing; scrapedURI=(${scrapedURI.id}, ${scrapedURI.state}, ${scrapedURI.url})")
          (scrapedURI, Some(article))
        }
      case NotScrapable(destinationUrl, redirects) =>
        val unscrapableURI = {
          syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
          val toBeSaved = processRedirects(latestUri, redirects).withState(NormalizedURIStates.UNSCRAPABLE)
          syncSaveNormalizedUri(toBeSaved)
        }
        log.info(s"[processURI] (${uri.url}) not scrapable; unscrapableURI=(${unscrapableURI.id}, ${unscrapableURI.state}, ${unscrapableURI.url}})")
        (unscrapableURI, None)
      case com.keepit.scraper.NotModified =>
        // update the scrape schedule, uri is not changed
        saveScrapeInfo(info.withDocumentUnchanged())
        log.info(s"[processURI] (${uri.url} not modified; latestUri=(${latestUri.id}, ${latestUri.state}, ${latestUri.url}})")
        (latestUri, None)
      case Error(httpStatus, msg) =>
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
        articleStore += (latestUri.id.get -> article)
        // the article is saved. update the scrape schedule and the state to SCRAPE_FAILED and save
        val errorURI = {
          syncSaveScrapeInfo(info.withFailure())
          syncSaveNormalizedUri(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
        log.warn(s"[processURI] Error($httpStatus, $msg); errorURI=(${errorURI.id}, ${errorURI.state}, ${errorURI.url})")
        (errorURI, None)
    }
  }

  def fetchArticle(normalizedUri: NormalizedURI, info: ScrapeInfo): ScraperResult = {
    try {
      URI.parse(normalizedUri.url) match {
        case Success(uri) =>
          uri.scheme match {
            case Some("file") => Error(-1, "forbidden scheme: %s".format("file"))
            case _ => fetchArticle(normalizedUri, httpFetcher, info)
          }
        case _ => fetchArticle(normalizedUri, httpFetcher, info)
      }
    } catch {
      case t: Throwable => {
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
        fetchArticle(normalizedUri, httpFetcher, info)
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

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo): ScraperResult = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    log.info(s"[fetchArticle] url=${normalizedUri.url} ${extractor.getClass}")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = syncGetProxyP(url)){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (isUnscrapableP(url, fetchStatus.destinationUrl)) {
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
        log.error(s"[fetchArticle] fetch failed ${normalizedUri.url} $info $httpFetcher;\nException: $e; Cause: ${e.getCause};\nStack trace:\n${e.getStackTrace.mkString(File.separator)}")
        Error(-1, "fetch failed: %s".format(e.toString))
      }
    }
  }

  private[this] def getTitle(x: Extractor): String = x.getMetadata("title").getOrElse("")

  private[this] def getDescription(x: Extractor): Option[String] = x.getMetadata("description")

  private[this] def getKeywords(x: Extractor): Option[String] = x.getKeywords

  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)

  private[this] def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    description = getDescription(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect]): NormalizedURI = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => updateRedirectRestriction(uri, redirect)
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => syncRecordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
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
    val wasKeptRecently = syncGetLatestBookmark(movedUri.id.get).map(_.updatedAt.isAfter(currentDateTime.minusHours(1))).getOrElse(false)
    hasFishy301Restriction || wasKeptRecently
    hasFishy301Restriction
  }

  // db helpers

  private[scraper] def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }

  private[scraper] def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI] = Await.result(getNormalizedUri(uri), 5 seconds)

  private[scraper] def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI] = shoeboxServiceClient.saveNormalizedURI(uri)

  private[scraper] def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI = Await.result(saveNormalizedUri(uri), 5 seconds)

  private[scraper] def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo] = shoeboxServiceClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE))

  private[scraper] def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = Await.result(saveScrapeInfo(info), 5 seconds)

  private[scraper] def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)

  private[scraper] def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark] = Await.result(getBookmarksByUriWithoutTitle(uriId), 5 seconds)

  private[scraper] def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = shoeboxServiceClient.getLatestBookmark(uriId)

  private[scraper] def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark] = Await.result(getLatestBookmark(uriId), 5 seconds)

  private[scraper] def saveBookmark(bookmark:Bookmark): Future[Bookmark] = shoeboxServiceClient.saveBookmark(bookmark)

  private[scraper] def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)

  private[scraper] def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = Await.result(recordPermanentRedirect(uri, redirect), 5 seconds)

  private[scraper] def getProxyP(url: String):Future[Option[HttpProxy]] = shoeboxServiceClient.getProxyP(url)

  private[scraper] def syncGetProxyP(url: String):Option[HttpProxy] = Await.result(getProxyP(url), 5 seconds)

  private[scraper] def asyncIsUnscrapableP(url: String, destinationUrl: Option[String]) = shoeboxServiceClient.isUnscrapableP(url, destinationUrl)

  protected def isUnscrapableP(url: String, destinationUrl: Option[String]) = Await.result(asyncIsUnscrapableP(url, destinationUrl), 5 seconds)


}