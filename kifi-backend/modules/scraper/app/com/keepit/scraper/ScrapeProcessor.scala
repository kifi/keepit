package com.keepit.scraper

import com.google.inject._
import play.api.{Play, Plugin}
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.search.{LangDetector, Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.shoebox.ShoeboxServiceClient
import java.io.File
import scala.concurrent.{Awaitable, Future, Await}
import scala.concurrent.duration._
import org.joda.time.Days
import com.keepit.common.time._
import com.keepit.common.net.URI
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.common.db.Id
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.routing.{SmallestMailboxRouter}
import play.api.Play.current
import scala.Some
import scala.util.Success


@ProvidedBy(classOf[ScrapeProcessorPluginProvider])
trait ScrapeProcessorPlugin extends Plugin {
  def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]):Future[(NormalizedURI, Option[Article])]
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]): Unit
}

@Singleton
class ScrapeProcessorPluginProvider @Inject() (
  scraperConfig: ScraperConfig,
  asyncScrapeProcessor:AsyncScrapeProcessorPlugin,
  syncScrapeProcessor:ScrapeProcessorPluginImpl
) extends Provider[ScrapeProcessorPlugin] with Logging {

  lazy val plugin = if (scraperConfig.async) asyncScrapeProcessor else syncScrapeProcessor
  log.info(s"[ScrapeProcessorPluginProvider] created with: $scraperConfig")

  def get = plugin
}

class ScrapeProcessorPluginImpl @Inject() (
  scraperConfig: ScraperConfig,
  sysProvider:   Provider[ActorSystem],
  procProvider:  Provider[ScrapeProcessor]
) extends ScrapeProcessorPlugin with Logging {

  lazy val system = sysProvider.get
  lazy val actor = {
    val nrOfInstances = if (Play.maybeApplication.isDefined && (!Play.isTest)) scraperConfig.numInstances else 1
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

case class AsyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])
case class FetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType])
case class ScrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])

class ScrapeProcessor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  syncScraper: SyncScraper,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks
) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScraperProcessor-actor] created $this")

  implicit val myConfig = config

  def receive = {

    case FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt) => {
      log.info(s"[FetchArticle] message received; url=$url")
      val ts = System.currentTimeMillis
      val extractor = extractorProviderTypeOpt match {
        case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
        case _ => extractorFactory(url)
      }
      val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
      val res = fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(syncScraper.basicArticle(url, extractor))
        case _ => None
      }
      log.info(s"[FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
      sender ! res
    }

    case ScrapeArticle(uri, info, proxyOpt) => {
      log.info(s"[ScrapeArticle] message received; url=${uri.url}")
      val ts = System.currentTimeMillis
      val res = syncScraper.safeProcessURI(uri, info, proxyOpt)
      log.info(s"[ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
      sender ! res
    }
    case AsyncScrape(nuri, info, proxyOpt) => {
      log.info(s"[AsyncScrape] message received; url=${nuri.url}")
      val ts = System.currentTimeMillis
      val (uri, a) = syncScraper.safeProcessURI(nuri, info, proxyOpt)
      log.info(s"[AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=(${uri.id}, ${uri.state})")
    }
  }

}

// straight port from original (local) code
class SyncScraper @Inject() (
  airbrake: AirbrakeNotifier,
  config: ScraperConfig,
  httpFetcher: HttpFetcher,
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
      log.error(s"[safeProcessURI] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
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
          signature.similarTo(Signature(info.signature)) >= (1.0d - config.changeThreshold * (config.minInterval / info.interval))
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
        log.error(s"[fetchArticle] Caught exception: $t; Cause: ${t.getCause}; \nStackTrace:\n${t.getStackTrace.mkString(File.separator)}")
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

  def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
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
    val wasKeptRecently = helper.syncGetLatestBookmark(movedUri.id.get).map(_.updatedAt.isAfter(currentDateTime.minusHours(1))).getOrElse(false)
    hasFishy301Restriction || wasKeptRecently
    hasFishy301Restriction
  }

}

class ShoeboxDbCallbackHelper @Inject() (config:ScraperConfig, shoeboxServiceClient:ShoeboxServiceClient) extends SyncShoeboxDbCallbacks with ShoeboxDbCallbacks {
  implicit val serviceCallTimeout = config.serviceCallTimeout

  private def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, config.syncAwaitTimeout seconds)

  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]) = await(isUnscrapableP(url, destinationUrl))
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI] = await(getNormalizedUri(uri))
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI = await(saveNormalizedUri(uri))
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = await(saveScrapeInfo(info))
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark] = await(getBookmarksByUriWithoutTitle(uriId))
  def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark] = await(getLatestBookmark(uriId))
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = await(recordPermanentRedirect(uri, redirect))
  def syncSaveBookmark(bookmark:Bookmark):Bookmark = await(saveBookmark(bookmark))

  def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }
  def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI] = shoeboxServiceClient.saveNormalizedURI(uri)
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo] = shoeboxServiceClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE))
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)
  def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = shoeboxServiceClient.getLatestBookmark(uriId)
  def saveBookmark(bookmark:Bookmark): Future[Bookmark] = shoeboxServiceClient.saveBookmark(bookmark)
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)
  def isUnscrapableP(url: String, destinationUrl: Option[String]) = shoeboxServiceClient.isUnscrapableP(url, destinationUrl)

}

@ImplementedBy(classOf[ShoeboxDbCallbackHelper])
trait SyncShoeboxDbCallbacks {
  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]):Boolean
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI]
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark]
  def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark]
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI
  def syncSaveBookmark(bookmark:Bookmark):Bookmark
}

@ImplementedBy(classOf[ShoeboxDbCallbackHelper])
trait ShoeboxDbCallbacks {
  def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]]
  def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI]
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]]
  def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]]
  def saveBookmark(bookmark:Bookmark): Future[Bookmark]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean]
}