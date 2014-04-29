package com.keepit.scraper

import com.google.inject._
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.search.Article
import scala.concurrent.Future

@ImplementedBy(classOf[QueuedScrapeProcessor])
trait ScrapeProcessor {
  def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]):Future[(NormalizedURI, Option[Article])]
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, pageInfo:Option[PageInfo], proxyOpt:Option[HttpProxy]):Unit
  def pull:Unit = {}
}
