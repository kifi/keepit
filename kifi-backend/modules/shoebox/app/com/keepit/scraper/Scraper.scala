package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.search.ArticleStore
import com.keepit.model._
import com.keepit.scraper.extractor.{ExtractorFactory, Extractor}
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.search.LangDetector
import org.apache.http.HttpStatus
import org.joda.time.Seconds
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.store.S3ScreenshotStore
import org.joda.time.Days
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.model.ScrapeInfo
import com.keepit.search.Article
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.normalizer.{TrustedCandidate, NormalizationService}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Success

object Scraper {
  val BATCH_SIZE = 100

  val maxContentChars = 100000 // 100K chars
}

@Singleton
class Scraper @Inject() (
  db: Database,
  httpFetcher: HttpFetcher,
  articleStore: ArticleStore,
  extractorFactory: ExtractorFactory,
  scraperConfig: ScraperConfig,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  healthcheckPlugin: HealthcheckPlugin,
  bookmarkRepo: BookmarkRepo,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  s3ScreenshotStore: S3ScreenshotStore,
  normalizationServiceProvider: Provider[NormalizationService])
    extends Logging {

  implicit val config = scraperConfig

  def run(): Seq[(NormalizedURI, Option[Article])] = {
    val startedTime = currentDateTime
    log.info("starting a new scrape round")
    val tasks = db.readOnly { implicit s =>
      scrapeInfoRepo.getOverdueList().map{ info => (normalizedURIRepo.get(info.uriId), info) }
    }
    log.info("got %s uris to scrape".format(tasks.length))
    val scrapedArticles = tasks.map{ case (uri, info) => safeProcessURI(uri, info) }
    val jobTime = Seconds.secondsBetween(startedTime, currentDateTime).getSeconds()
    log.info("succesfuly scraped %s articles out of %s in %s seconds:\n%s".format(
        scrapedArticles.flatMap{ a => a._2 }.size, tasks.size, jobTime, scrapedArticles map {a => a._1} mkString "\n"))
    scrapedArticles
  }

  def safeProcessURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = {
    val info = db.readWrite { implicit s =>
      scrapeInfoRepo.getByUri(uri.id.get).getOrElse(scrapeInfoRepo.save(ScrapeInfo(uriId = uri.id.get)))
    }
    safeProcessURI(uri, info)
  }

  def getBasicArticle(url: String, customExtractor: Option[Extractor] = None): Option[BasicArticle] = {
    val extractor = customExtractor.getOrElse(extractorFactory(url))
    try {
      val fetchStatus = httpFetcher.fetch(url, proxy = getProxy(url)) { input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(isUnscrapable(url, fetchStatus.destinationUrl)) => Some(basicArticle(url, extractor))
        case _ => None
      }
    } catch {
      case e: Throwable => None
    }
  }

  private def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = try {
      processURI(uri, info)
    } catch {
      case e: Throwable =>
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL))
        val errorURI = db.readWrite { implicit s =>
          // first update the uri state to SCRAPE_FAILED
          val latestUri = normalizedURIRepo.get(uri.id.get)
          val savedUri = if (latestUri.state == NormalizedURIStates.INACTIVE) latestUri else normalizedURIRepo.save(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
          // then update the scrape schedule
          scrapeInfoRepo.save(info.withFailure())
          savedUri
        }
        (errorURI, None)
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


  private def processURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = {
    log.debug(s"scraping $uri")

    val fetchedArticle = fetchArticle(uri, info)
    db.readWrite { implicit s =>
      val latestUri = normalizedURIRepo.get(uri.id.get)
      if (latestUri.state == NormalizedURIStates.INACTIVE) (latestUri, None)
      else fetchedArticle match {
        case Scraped(article, signature, redirects) =>
          // store a scraped article in a store map
          articleStore += (latestUri.id.get -> article)

          val scrapedURI = {
            // first update the uri state to SCRAPED
            val toBeSaved = processRedirects(latestUri, redirects).withTitle(article.title).withState(NormalizedURIStates.SCRAPED)
            val savedUri = normalizedURIRepo.save(toBeSaved)
            // then update the scrape schedule
            scrapeInfoRepo.save(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
            bookmarkRepo.getByUriWithoutTitle(savedUri.id.get).foreach { bookmark =>
              bookmarkRepo.save(bookmark.copy(title = savedUri.title))
            }
            savedUri
          }
          log.debug("fetched uri %s => %s".format(scrapedURI, article))


          def shouldUpdateScreenshot(uri: NormalizedURI) = {
            uri.screenshotUpdatedAt map { update =>
              Days.daysBetween(currentDateTime.toDateMidnight, update.toDateMidnight).getDays() >= 5
            } getOrElse true
          }
          if(shouldUpdateScreenshot(scrapedURI))
            s3ScreenshotStore.updatePicture(scrapedURI)

          (scrapedURI, Some(article))
        case NotScrapable(destinationUrl, redirects) =>
          val unscrapableURI = {
            scrapeInfoRepo.save(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
            val toBeSaved = processRedirects(latestUri, redirects).withState(NormalizedURIStates.UNSCRAPABLE)
            normalizedURIRepo.save(toBeSaved)
          }
          (unscrapableURI, None)
        case NotModified =>
          // update the scrape schedule, uri is not changed
          scrapeInfoRepo.save(info.withDocumentUnchanged())
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
            scrapeInfoRepo.save(info.withFailure())
            normalizedURIRepo.save(latestUri.withState(NormalizedURIStates.SCRAPE_FAILED))
          }
          (errorURI, None)
      }
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

  protected def isUnscrapable(url: String, destinationUrl: Option[String]) = {
    db.readOnly { implicit s =>
      (urlPatternRuleRepo.isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.isUnscrapable(destinationUrl.get)))
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo): ScraperResult = {
    val url = normalizedUri.url
    val extractor = extractorFactory(url)
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince, proxy = getProxy(url)){ input => extractor.process(input) }

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
            val signature = Signature(Seq(title, description.getOrElse(""), content))

            // now detect the document change
            val docChanged = {
              normalizedUri.title != Option(title) || // title change should always invoke indexing
              signature.similarTo(Signature(info.signature)) < (1.0d - config.changeThreshold * (config.minInterval / info.interval))
            }

            // if unchanged, don't trigger indexing. buf if SCRAPE_WANTED or SCRAPE_FAILED, we always change the state and invoke indexing.
            if (!docChanged &&
                normalizedUri.state != NormalizedURIStates.SCRAPE_WANTED &&
                normalizedUri.state != NormalizedURIStates.SCRAPE_FAILED) {
              NotModified
            } else {
              val contentLang = description match {
                case Some(desc) => LangDetector.detect(content + " " + desc)
                case None => LangDetector.detect(content)
              }
              val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language
              Scraped(Article(
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
                  destinationUrl = fetchStatus.destinationUrl
               ),
               signature,
               fetchStatus.redirects)
            }
          }
        case HttpStatus.SC_NOT_MODIFIED =>
          NotModified
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

  private[this] def getProxy(url: String) = db.readOnly { implicit session => urlPatternRuleRepo.getProxy(url) }

  def close() {
    httpFetcher.close()
  }

  private[this] def basicArticle(destinationUrl: String, extractor: Extractor): BasicArticle = BasicArticle(
    title = getTitle(extractor),
    content = extractor.getContent,
    description = getDescription(extractor),
    media = getMediaTypeString(extractor),
    httpContentType = extractor.getMetadata("Content-Type"),
    httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
    destinationUrl = Some(destinationUrl)
  )

  private def processRedirects(uri: NormalizedURI, redirects: Seq[HttpRedirect])(implicit session: RWSession): NormalizedURI = {
    redirects.find(_.isLocatedAt(uri.url)) match {
      case Some(redirect) if !redirect.isPermanent || hasFishy301(uri) => updateRedirectRestriction(uri, redirect)
      case Some(permanentRedirect) if permanentRedirect.isAbsolute => recordPermanentRedirect(removeRedirectRestriction(uri), permanentRedirect)
      case _ => removeRedirectRestriction(uri)
    }
  }

  private def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect)(implicit session: RWSession): NormalizedURI = {
    require(redirect.isPermanent, "HTTP redirect is not permanent.")
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
    val toBeRedirected = for {
      candidateUri <- normalizedURIRepo.getByUri(redirect.newDestination)
      normalization <- candidateUri.normalization
    } yield {
      val toBeRedirected = uri.withNormalization(Normalization.MOVED)
      session.onTransactionSuccess(normalizationServiceProvider.get.update(toBeRedirected, TrustedCandidate(candidateUri.url, normalization)))
      toBeRedirected
    }

    toBeRedirected getOrElse uri
  }

  private def removeRedirectRestriction(uri: NormalizedURI): NormalizedURI = uri.restriction match {
    case Some(restriction) if Restriction.redirects.contains(restriction) => uri.copy(restriction = None)
    case _ => uri
  }

  private def updateRedirectRestriction(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = {
    val restriction = Restriction.http(redirect.statusCode)
    if (Restriction.redirects.contains(restriction)) uri.copy(restriction = Some(restriction)) else removeRedirectRestriction(uri)
  }

  private def hasFishy301(movedUri: NormalizedURI)(implicit session: RSession): Boolean = {
    val hasFishy301Restriction = movedUri.restriction == Some(Restriction.http(301))
    val wasKeptRecently = bookmarkRepo.latestBookmark(movedUri.id.get).map(_.updatedAt.isAfter(currentDateTime.minusHours(1))).getOrElse(false)
    hasFishy301Restriction || wasKeptRecently
  }
}
