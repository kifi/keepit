package com.keepit.scraper

import play.api.Plugin
import scala.concurrent.Future
import com.keepit.model.NormalizedURI
import com.keepit.search.Article
import com.keepit.scraper.extractor.{ExtractorProviderType, Extractor}
import com.keepit.common.db.slick.DBSession.RWSession

trait ScraperPlugin extends Plugin {
  def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]]
  def scheduleScrape(uri: NormalizedURI)(implicit session: RWSession): Unit
  def scrapeBasicArticle(url: String): Future[Option[BasicArticle]]
  def scrapeBasicArticleWithExtractor(url: String, extractorProviderType:Option[ExtractorProviderType] = None): Future[Option[BasicArticle]]
}
