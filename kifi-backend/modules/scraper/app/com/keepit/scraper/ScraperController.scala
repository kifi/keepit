package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.controller.{WebsiteController, ScraperServiceController, ActionAuthenticator}
import com.keepit.model._
import play.api.mvc.{SimpleResult, AsyncResult, Action}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.scraper.extractor.{Extractor, ExtractorFactory}
import org.apache.http.HttpStatus
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.search.{ArticleStore, Article, LangDetector}
import com.keepit.common.time._
import com.keepit.model.ScrapeInfo
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.Days
import scala.util.Success
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.common.store.S3ScreenshotStore

class ScraperController @Inject() (
  airbrake: AirbrakeNotifier,
  actionAuthenticator:ActionAuthenticator,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  scraperConfig: ScraperConfig,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  shoeboxServiceClient: ShoeboxServiceClient
) extends WebsiteController(actionAuthenticator) with ScraperServiceController with Logging {

  implicit val config = scraperConfig

  // TODO: polling/queue

  def getBasicArticle(url:String) = Action { request =>
    val extractor = extractorFactory(url)
    val res = try {
      val fetchStatus = httpFetcher.fetch(url, proxy = syncGetProxy(url)) { input => extractor.process(input) }
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(isUnscrapable(url, fetchStatus.destinationUrl)) => Some(basicArticle(url, extractor))
        case _ => None
      }
    } catch {
      case e: Throwable => None
    }
    val json = Json.toJson(res)
    log.info(s"[getBasicArticle($url)=$res json=${Json.prettyPrint(json)}")
    Ok(json)
  }

  def asyncScrape() = Action(parse.json) { request =>
    val normalizedUri = request.body.as[NormalizedURI]
    val info = Await.result(shoeboxServiceClient.getScrapeInfo(normalizedUri), 5 seconds)
    log.info(s"[asyncScrape] url=${normalizedUri.url} $info")
    val t = safeProcessURI(normalizedUri, info)
    val res = ScrapeTuple(t._1, t._2)
    Ok(Json.toJson(res))
  }

  private[scraper] def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = try {
    processURI(uri, info)
  } catch {
    case e: Throwable => {
      log.error("uncaught exception while scraping uri %s".format(uri), e)
      airbrake.notify(e)
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
    log.info(s"[processURI] scraping $uri $info")
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
          // the article does not need to be reindexed
          // update the scrape schedule, uri is not changed
          saveScrapeInfo(info.withDocumentUnchanged())
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
          log.debug("fetched uri %s => %s".format(scrapedURI, article))

          def shouldUpdateScreenshot(uri: NormalizedURI) = {
            uri.screenshotUpdatedAt map { update =>
              Days.daysBetween(currentDateTime.toDateMidnight, update.toDateMidnight).getDays() >= 5
            } getOrElse true
          }
          if(shouldUpdateScreenshot(scrapedURI)) s3ScreenshotStore.updatePicture(scrapedURI)

          (scrapedURI, Some(article))
        }
      case NotScrapable(destinationUrl, redirects) =>
        val unscrapableURI = {
          syncSaveScrapeInfo(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
          val toBeSaved = processRedirects(latestUri, redirects).withState(NormalizedURIStates.UNSCRAPABLE)
          syncSaveNormalizedUri(toBeSaved)
        }
        (unscrapableURI, None)
      case com.keepit.scraper.NotModified =>
        // update the scrape schedule, uri is not changed
        saveScrapeInfo(info.withDocumentUnchanged())
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
      case _: Throwable => fetchArticle(normalizedUri, httpFetcher, info)
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
    log.info(s"[fetchArticle] $normalizedUri $extractor")
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = syncGetProxy(url)){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (isUnscrapable(url, fetchStatus.destinationUrl)) {
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

            val res = Scraped(Article(
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
              destinationUrl = fetchStatus.destinationUrl),
              signature,
              fetchStatus.redirects)
            log.info(s"[fetchArticle] result=$res")
            res
          }
        case HttpStatus.SC_NOT_MODIFIED =>
          com.keepit.scraper.NotModified
        case _ =>
          Error(fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed"))
      }
    } catch {
      case e: Throwable => Error(-1, "fetch failed: %s".format(e.toString))
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

  private[scraper] def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo] = shoeboxServiceClient.saveScrapeInfo(info)

  private[scraper] def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = Await.result(saveScrapeInfo(info), 5 seconds)

  private[scraper] def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)

  private[scraper] def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark] = Await.result(getBookmarksByUriWithoutTitle(uriId), 5 seconds)

  private[scraper] def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = shoeboxServiceClient.getLatestBookmark(uriId)

  private[scraper] def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark] = Await.result(getLatestBookmark(uriId), 5 seconds)

  private[scraper] def saveBookmark(bookmark:Bookmark): Future[Bookmark] = shoeboxServiceClient.saveBookmark(bookmark)

  private[scraper] def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)

  private[scraper] def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = Await.result(recordPermanentRedirect(uri, redirect), 5 seconds)

  private[scraper] def getProxy(url: String):Future[Option[HttpProxy]] = shoeboxServiceClient.getProxy(url)

  private[scraper] def syncGetProxy(url: String):Option[HttpProxy] = Await.result(getProxy(url), 5 seconds)

  private[scraper] def asyncIsUnscrapable(url: String, destinationUrl: Option[String]) = shoeboxServiceClient.isUnscrapable(url, destinationUrl)

  protected def isUnscrapable(url: String, destinationUrl: Option[String]) = Await.result(asyncIsUnscrapable(url, destinationUrl), 5 seconds)
}