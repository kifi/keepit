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
import play.api.Play.current

class Scraper(articleStore: ArticleStore) extends Logging {
  val config = new CrawlConfig()
  val pageFetcher = new PageFetcher(config)
  val parser = new Parser(config);
  
  def run() {
    try {
      val uris = CX.withConnection { implicit c =>
        NormalizedURI.getByState(ACTIVE)
      }
      processURIs(uris)
    } catch {
      case ex: Throwable => log.error("error in scaper run", ex) // log and eat the exception
    }
  }
  
  def processURIs(uris: Seq[NormalizedURI]) {
    uris.map{ uri =>
      fetchArticle(uri) match {
        case Left(article) =>
          // store article in a store map
          try {
            articleStore += (uri.id.get -> article)
            // succeeded. update the state to SCRAPED and save
            CX.withConnection { implicit c =>
              uri.withState(NormalizedURI.States.SCRAPED).save
            }
          } catch {
            case ex:Exception => log.error("failed to store an article: uri.id=" + uri.id.get.id)
          }
        case Right(error) =>
          CX.withConnection { implicit c =>
            uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
          }
      }
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
      		  Left(Article(normalizedUri, title, content)) // return Article             
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
