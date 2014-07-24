package com.keepit.scraper

import com.keepit.model.{ PageInfo, ScrapeInfo, NormalizedURI, HttpProxy }
import com.keepit.scraper.extractor.ExtractorProviderType

import scala.concurrent.Future

trait ScrapeProcessor {
  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]]
  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit
  def pull: Unit = {}
}
