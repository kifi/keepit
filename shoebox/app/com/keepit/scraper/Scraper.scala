package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model._
import com.keepit.scraper.extractor.DefaultExtractor
import com.keepit.scraper.extractor.DefaultExtractorFactory
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.YoutubeExtractorFactory
import com.keepit.search.LangDetector
import com.google.inject.Inject
import org.apache.http.HttpStatus
import org.joda.time.{DateTime, Seconds}
import play.api.Play.current
import scala.util.{Failure, Success}
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}

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
  unscrapableRepo: UnscrapableRepo)
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

  private def safeProcessURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = try {
      processURI(uri, info)
    } catch {
      case e: Throwable =>
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL))
        val errorURI = db.readWrite { implicit s =>
          scrapeInfoRepo.save(info.withFailure())
          normalizedURIRepo.save(uri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
        (errorURI, None)
    }

  private def getIfModifiedSince(info: ScrapeInfo) = {
    info.signature match {
      case "" => None // no signature. this is the first time
      case _ => Some(info.lastScrape)
    }
  }


  private def processURI(uri: NormalizedURI, info: ScrapeInfo): (NormalizedURI, Option[Article]) = {
    log.info(s"scraping $uri")

    fetchArticle(uri, info) match {
      case Scraped(article, signature) =>
        // store a scraped article in a store map
        articleStore += (uri.id.get -> article)

        val scrapedURI = db.readWrite { implicit s =>
          // update the scrape schedule and the uri state to SCRAPED
          scrapeInfoRepo.save(info.withDestinationUrl(article.destinationUrl).withDocumentChanged(signature.toBase64))
          normalizedURIRepo.saveAsIndexable(uri.withTitle(article.title).withState(NormalizedURIStates.SCRAPED))
        }
        log.info("fetched uri %s => %s".format(uri, article))
        (scrapedURI, Some(article))
      case NotScrapable(destinationUrl) =>
        val unscrapableURI = db.readWrite { implicit s =>
          scrapeInfoRepo.save(info.withDestinationUrl(destinationUrl).withDocumentUnchanged())
          normalizedURIRepo.saveAsIndexable(uri.withState(NormalizedURIStates.UNSCRAPABLE))
        }
        (unscrapableURI, None)
      case NotModified =>
        // update the scrape schedule, uri is not changed
        db.readWrite { implicit s => scrapeInfoRepo.save(info.withDocumentUnchanged()) }
        (uri, None)
      case Error(httpStatus, msg) =>
        // store a fallback article in a store map
        val article = Article(
            id = uri.id.get,
            title = uri.title.getOrElse(""),
            content = "",
            scrapedAt = currentDateTime,
            httpContentType = None,
            httpOriginalContentCharset = None,
            state = NormalizedURIStates.SCRAPE_FAILED,
            message = Option(msg),
            titleLang = None,
            contentLang = None,
            destinationUrl = None)
        articleStore += (uri.id.get -> article)
        // the article is saved. update the scrape schedule and the state to SCRAPE_FAILED and save
        val errorURI = db.readWrite { implicit s =>
          scrapeInfoRepo.save(info.withFailure())
          normalizedURIRepo.saveAsIndexable(uri.withState(NormalizedURIStates.SCRAPE_FAILED))
        }
        (errorURI, None)
    }
  }

  protected def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Success(uri) =>
          Extractor.factories.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find a extractor factory"))
        case Failure(_) =>
          log.warn("uri parsing failed: [%s]".format(url))
          new DefaultExtractor(url, Scraper.maxContentChars)
      }
    } catch {
      case e: Throwable =>
          log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
          new DefaultExtractor(url, Scraper.maxContentChars)
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
    val ifModifiedSince = getIfModifiedSince(info)

    try {
      val fetchStatus = httpFetcher.fetch(url, ifModifiedSince){ input => extractor.process(input) }

      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          if (isUnscrapable(url, fetchStatus.destinationUrl)) {
            NotScrapable(fetchStatus.destinationUrl)
          } else {
            val content = extractor.getContent
            val title = extractor.getMetadata("title").getOrElse("")
            val signature = computeSignature(title, content)

            // now detect the document change
            val docChanged = signature.similarTo(Signature(info.signature)) < (1.0d - config.changeThreshold * (config.minInterval / info.interval))

            if (!docChanged) {
              NotModified
            } else {
              val contentLang = LangDetector.detect(content)
              val titleLang = LangDetector.detect(title, contentLang) // bias the detection using the content language
              Scraped(Article(id = normalizedUri.id.get,
                              title = title,
                              content = content,
                              scrapedAt = currentDateTime,
                              httpContentType = extractor.getMetadata("Content-Type"),
                              httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
                              state = NormalizedURIStates.SCRAPED,
                              message = None,
                              titleLang = Some(titleLang),
                              contentLang = Some(contentLang),
                              destinationUrl = fetchStatus.destinationUrl),
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

  private[this] def computeSignature(fields: String*) = fields.foldLeft(new SignatureBuilder){ (builder, text) => builder.add(text) }.build

  def close() {
    httpFetcher.close()
  }
}
