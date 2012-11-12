package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.db.{Id, CX}
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.scraper.extractor.DefaultExtractor
import com.keepit.scraper.extractor.DefaultExtractorFactory
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.YoutubeExtractorFactory
import com.google.inject.Inject
import org.apache.http.HttpStatus
import org.joda.time.Seconds
import play.api.Play.current

object Scraper {
  val BATCH_SIZE = 100
  
  val maxContentChars = 100000 // 100K chars
}

class Scraper @Inject() (articleStore: ArticleStore) extends Logging {
  
  val httpFetcher = new HttpFetcher
  
  def run(): Seq[(NormalizedURI, Option[Article])] = {
    val startedTime = currentDateTime
    log.info("starting a new scrape round")
    val uris = CX.withConnection { implicit c =>
      NormalizedURI.getByState(ACTIVE, Scraper.BATCH_SIZE)
    }
    log.info("got %s uris to scrape".format(uris.length))
    val scrapedArticles = processURIs(uris)
    val jobTime = Seconds.secondsBetween(startedTime, currentDateTime).getSeconds()
    log.info("succesfuly scraped %s articles out of %s in %s seconds:\n%s".format(
        scrapedArticles.size, uris.size, jobTime, scrapedArticles map {a => a._1} mkString "\n"))
    scrapedArticles
  }
  
  def processURIs(uris: Seq[NormalizedURI]): Seq[(NormalizedURI, Option[Article])] = uris map safeProcessURI
  
  def safeProcessURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = try {
      processURI(uri)
    } catch {
      case e => 
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  
  
  private def processURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = {
    log.info("scraping %s".format(uri))
    fetchArticle(uri) match {
      case Left(article) =>
        // store a scraped article in a store map
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPED and save
        val scrapedURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPED).save
        }
        log.info("fetched uri %s => %s".format(uri, article))
        (scrapedURI, Some(article))
      case Right(error) =>
        // store a fallback article in a store map
        val article = Article(
            id = uri.id.get,
            title = uri.title,
            content = "",
            scrapedAt = currentDateTime,
            httpContentType = None,
            httpOriginalContentCharset = None,
            NormalizedURI.States.SCRAPE_FAILED,
            Option(error.msg))
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPE_FAILED and save
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  }
  
  private def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Some(uri) =>
          Extractor.factories.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find a extractor factory"))
        case None =>
          log.warn("uri parsing failed: [%s]".format(url))
          new DefaultExtractor(url, Scraper.maxContentChars)
      }
    } catch {
      case e => 
          log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
          new DefaultExtractor(url, Scraper.maxContentChars)
    }
  }
  
  def fetchArticle(normalizedUri: NormalizedURI): Either[Article, ScraperError] = {
    fetchArticle(normalizedUri, httpFetcher)
  }
  
  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher): Either[Article, ScraperError] = {
    var url = normalizedUri.url
    val extractor = getExtractor(url)
    
    try {
      val fetchStatus = httpFetcher.fetch(url){ input => extractor.process(input) }
      
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          val title = extractor.getMetadata("title").getOrElse("")
          val content = extractor.getContent
          Left(Article(id = normalizedUri.id.get,
                       title = title,
                       content = content,
                       scrapedAt = currentDateTime,
                       httpContentType = extractor.getMetadata("Content-Type"),
                       httpOriginalContentCharset = extractor.getMetadata("Content-Encoding"),
                       state = SCRAPED,
                       message = None))
        case _ =>
          Right(ScraperError(normalizedUri, fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed")))
      }
    } catch {
      case e => Right(ScraperError(normalizedUri, -1, "fetch failed: %s".format(e.toString)))
    }
  }
  
  def close() {
    httpFetcher.close()
  }
}

