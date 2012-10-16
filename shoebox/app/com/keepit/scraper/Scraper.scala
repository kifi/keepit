package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import edu.uci.ics.crawler4j.crawler.{CrawlConfig, Page }
import edu.uci.ics.crawler4j.fetcher.{CustomFetchStatus, PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.parser.{HtmlParseData, Parser}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus
import com.google.inject.Inject
import play.api.Play.current
import org.joda.time.Seconds

object Scraper {
  val BATCH_SIZE = 100
}

class Scraper @Inject() (articleStore: ArticleStore) extends Logging {
  val config = {
    val conf = new CrawlConfig()
    conf.setIncludeHttpsPages(true)
    conf
  }
  
  val pageFetcher = new PageFetcher(config)
  val parser = new Parser(config);
  
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
  
  def processURIs(uris: Seq[NormalizedURI]): Seq[(NormalizedURI, Option[Article])] = uris.par map { uri =>
    try {
      processURI(uri)
    } catch {
      case e => 
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  } seq
  
  def processURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = {
    log.info("scraping %s".format(uri))
    fetchArticle(uri) match {
      case Left(article) =>
        // store article in a store map
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPED and save
        val scrapedURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPED).save
        }
        log.info("fetched uri %s => %s".format(uri, article))
        (scrapedURI, Some(article))
      case Right(error) =>
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  }
  
  def fetchArticle(normalizedUri: NormalizedURI): Either[Article, ScraperError] = {
    var fetchResult: Option[PageFetchResult] = None
    val webURL = new WebURL
    webURL.setURL(normalizedUri.url)
    try {
      val result = pageFetcher.fetchHeader(webURL)
      fetchResult = Some(result)
      val statusCode = result.getStatusCode()
      
      if (statusCode == HttpStatus.SC_OK) {
      	// the status is OK. now scrape it.
        val page = new Page(webURL)
        if (result.fetchContent(page) && parser.parse(page, normalizedUri.url)) {
          page.getParseData() match {
      	    case htmlData: HtmlParseData =>
      	      val title = htmlData.getTitle()
              val content = htmlData.getText()
      		  Left(Article(normalizedUri.id.get, title, content)) // return Article             
      		case _ => Right(ScraperError(normalizedUri, statusCode, "not html"))
          }
      	} else {
          Right(ScraperError(normalizedUri, statusCode, "parse failed"))
        }
      } else if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
        // TODO: redirect?
        // val movedToUrl = result.getMovedToUrl();
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode))))
      } else if (result.getStatusCode() == CustomFetchStatus.PageTooBig) {
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s [%s]".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode), "revisit crawler4j config")))
      } else {
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode))))
      }
    } finally {
      fetchResult.foreach(_.discardContentIfNotConsumed())
    }
  }
}
