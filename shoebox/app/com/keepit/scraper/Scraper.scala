package com.keepit.scraper

import com.keepit.search.Article

import edu.uci.ics.crawler4j.crawler.{CrawlConfig, Page, WebCrawler}
import edu.uci.ics.crawler4j.fetcher.{CustomFetchStatus, PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.parser.{HtmlParseData, Parser}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus;

class Scraper {
  val config = new CrawlConfig()
  val pageFetcher = new PageFetcher(config)
  val parser = new Parser(config);
  
  def fetchArticle(urlId: Long, url: String): Article = {
    var fetchResult: PageFetchResult = null
    val webURL = new WebURL
    webURL.setURL(url)
    try {
      val fetchResult = pageFetcher.fetchHeader(webURL);
      val statusCode = fetchResult.getStatusCode()
      if (statusCode != HttpStatus.SC_OK) {
        if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
          // TODO: redirect?
          // val movedToUrl = fetchResult.getMovedToUrl();
        } else if (fetchResult.getStatusCode() == CustomFetchStatus.PageTooBig) {
          // logger.info("Skipping a page which was bigger than max allowed size: " + curURL.getURL());
        }
        throw new ScraperException(statusCode, "error: htmlStatusCode=" + statusCode)
      } else {
        // the status is OK. now scrape it
        val page = new Page(webURL)
        if (fetchResult.fetchContent(page) && parser.parse(page, url)) {
          page.getParseData() match {
            case htmlData: HtmlParseData =>
              val title = htmlData.getTitle()
              val content = htmlData.getText()
              Article(urlId, url, title, content)
            case _ => throw new ScraperException(statusCode, "not html")
          }
        } else {
          throw new ScraperException(statusCode, "parse failed")
        }
      }
    } finally {
      if (fetchResult != null) fetchResult.discardContentIfNotConsumed();
    }
  }

  class ScraperException(val httpStatusCode: Int, msg: String) extends Exception(msg)
}

  