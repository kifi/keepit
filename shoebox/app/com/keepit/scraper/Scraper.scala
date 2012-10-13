package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import edu.uci.ics.crawler4j.crawler.{CrawlConfig, Page }
import edu.uci.ics.crawler4j.fetcher.{CustomFetchStatus, PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.parser.{HtmlParseData, Parser}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus;
import com.google.inject.Inject

import play.api.Play.current

class Scraper @Inject() (articleStore: ArticleStore) extends Logging {
  val config = new CrawlConfig()
  val pageFetcher = new PageFetcher(config)
  val parser = new Parser(config);
  
  def run(): Int = {
    log.info("starting a new scrape round")
    val uris = CX.withConnection { implicit c =>
      NormalizedURI.getByState(ACTIVE)
    }
    log.info("got %s uris to scrape".format(uris.length))
    processURIs(uris).size
  }
  
  def processURIs(uris: Seq[NormalizedURI]): Seq[Article] = uris map processURI flatten
  
  def processURI(uri: NormalizedURI): Option[Article] = {
    log.info("scraping %s".format(uri))
    fetchArticle(uri) match {
      case Left(article) =>
        // store article in a store map
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPED and save
        CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPED).save
        }
        log.info("fetched uri %s => %s".format(uri, article))
        Some(article)
      case Right(error) =>
        CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        None
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
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=" + statusCode))
      } else if (result.getStatusCode() == CustomFetchStatus.PageTooBig) {
        // logger.info("Skipping a page which was bigger than max allowed size: " + curURL.getURL());
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: page size exceeded maximum. revisit crawler4j config"))
      } else {
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=" + statusCode))
      }
    } finally {
      fetchResult.foreach(_.discardContentIfNotConsumed())
    }
  }
}
