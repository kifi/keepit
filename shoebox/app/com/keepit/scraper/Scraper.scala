package com.keepit.scraper

import com.keepit.search.Article
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import edu.uci.ics.crawler4j.crawler.{CrawlConfig, Page, WebCrawler}
import edu.uci.ics.crawler4j.fetcher.{CustomFetchStatus, PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.parser.{HtmlParseData, Parser}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus;

class Scraper {
  val config = new CrawlConfig()
  val pageFetcher = new PageFetcher(config)
  val parser = new Parser(config);
  
  def fetchArticle(normalizedUri: NormalizedURI): Either[Article, ScraperError] = {
    var fetchResult: PageFetchResult = null
    val webURL = new WebURL
    webURL.setURL(normalizedUri.url)
    try {
      val fetchResult = pageFetcher.fetchHeader(webURL);
      val statusCode = fetchResult.getStatusCode()
      if (statusCode != HttpStatus.SC_OK) {
        if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
          // TODO: redirect?
          // val movedToUrl = fetchResult.getMovedToUrl();
          Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=" + statusCode))
        } else if (fetchResult.getStatusCode() == CustomFetchStatus.PageTooBig) {
          // logger.info("Skipping a page which was bigger than max allowed size: " + curURL.getURL());
          Right(ScraperError(normalizedUri, statusCode, "fetch failed: page size exceeded maximum. revisit crawler4j config"))
        } else {
          Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=" + statusCode))
        }
      } else {
        // the status is OK. now scrape it
        val page = new Page(webURL)
        if (fetchResult.fetchContent(page) && parser.parse(page, normalizedUri.url)) {
          page.getParseData() match {
            case htmlData: HtmlParseData =>
              val title = htmlData.getTitle()
              val content = htmlData.getText()
              Left(Article(normalizedUri.copy(state = SCRAPED), title, content)) // return Article             
            case _ => Right(ScraperError(normalizedUri.copy(state = SCRAPE_FAILED), statusCode, "not html"))
          }
        } else {
          Right(ScraperError(normalizedUri.copy(state = SCRAPE_FAILED), statusCode, "parse failed"))
        }
      }
    } finally {
      if (fetchResult != null) fetchResult.discardContentIfNotConsumed();
    }
  }
}
