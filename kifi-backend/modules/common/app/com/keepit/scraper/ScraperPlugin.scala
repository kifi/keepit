package com.keepit.scraper

import play.api.Plugin
import scala.concurrent.Future
import com.keepit.model.NormalizedURI
import com.keepit.search.Article
import com.keepit.scraper.extractor.Extractor

trait ScraperPlugin extends Plugin {
  def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]]
  def scheduleScrape(uri: NormalizedURI): Unit
  def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]]
}
