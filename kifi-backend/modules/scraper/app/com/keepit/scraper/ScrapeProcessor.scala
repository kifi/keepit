package com.keepit.scraper

import com.keepit.model._
import com.keepit.scraper.extractor.ExtractorProviderType

import scala.concurrent.Future

trait ScrapeProcessor {
  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]]
  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Unit
  def status(): Future[Seq[ScrapeJobStatus]] = Future.successful(Seq.empty)
  def pull(): Unit = {}
}

