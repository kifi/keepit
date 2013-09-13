package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.search.ArticleStore
import com.keepit.model._
import com.keepit.scraper.extractor.DefaultExtractorProvider
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.search.LangDetector
import org.apache.http.HttpStatus
import org.joda.time.Seconds
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.store.S3ScreenshotStore
import org.joda.time.Days
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import scala.util.Failure
import scala.Some
import com.keepit.model.NormalizedURI
import com.keepit.common.healthcheck.HealthcheckError
import scala.util.Success
import com.keepit.model.ScrapeInfo
import com.keepit.search.Article
import com.keepit.common.net.URI

object Scraper {
  val BATCH_SIZE = 100

  val maxContentChars = 100000 // 100K chars
}

class Scraper @Inject() (
  db: Database,
  httpFetcher: HttpFetcher,
  articleStore: ArticleStore,
  scraperConfig: ScraperConfig,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  healthcheckPlugin: HealthcheckPlugin,
  bookmarkRepo: BookmarkRepo,
  unscrapableRepo: UnscrapableRepo,
  s3ScreenshotStore: S3ScreenshotStore)
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
    val extractor = customExtractor.getOrElse(getExtractor(url))
    try {
      val fetchStatus = httpFetcher.fetch(url) { input => extractor.process(input) }

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
        case Scraped(article, signature) =>
          // store a scraped article in a store map
          articleStore += (latestUri.id.get -> article)

          val scrapedURI = {
            // first update the uri state to SCRAPED
            val savedUri = normalizedURIRepo.save(latestUri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))
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
        case NotScrapable(destinationUrl) =>
          val unscrapableURI = {
            scrapeInfoRepo.save(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
            normalizedURIRepo.save(latestUri.withState(NormalizedURIStates.UNSCRAPABLE))
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

  protected def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Success(uri) =>
          Extractor.factories.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find an extractor factory"))
        case Failure(_) =>
          log.warn("uri parsing failed: [%s]".format(url))
          DefaultExtractorProvider(url)
      }
    } catch {
      case e: Throwable =>
          log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
          DefaultExtractorProvider(url)
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
      (unscrapableRepo.contains(url) || (destinationUrl.isDefined && unscrapableRepo.contains(destinationUrl.get)))
    }
  }

  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher, info: ScrapeInfo): ScraperResult = {
    val url = normalizedUri.url
    val extractor = getExtractor(url)
    val ifModifiedSince = getIfModifiedSince(normalizedUri, info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (isUnscrapable(url, fetchStatus.destinationUrl)) {
            NotScrapable(fetchStatus.destinationUrl)
          } else {
            val content = extractor.getContent
            val title = getTitle(extractor)
            val description = getDescription(extractor)
            val keywords = getKeywords(extractor)
            val media = getMediaTypeString(extractor)
            val signature = Signature(Seq(title, description.getOrElse(""), content))

            // now detect the document change
            val docChanged = signature.similarTo(Signature(info.signature)) < (1.0d - config.changeThreshold * (config.minInterval / info.interval))

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
               signature)
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

  private[this] def getTitle(x: Extractor): String = {
    x.getMetadata("title").getOrElse("")
  }
  private[this] def getDescription(x: Extractor): Option[String] = {
    x.getMetadata("description").orElse(x.getMetadata("Description")).orElse(x.getMetadata("DESCRIPTION"))
  }
  private[this] def getKeywords(x: Extractor): Option[String] = {
    x.getKeywords
  }
  private[this] def getMediaTypeString(x: Extractor): Option[String] = MediaTypes(x).getMediaTypeString(x)


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
}
