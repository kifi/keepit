package com.keepit.scraper

import com.google.inject._
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.scraper.extractor._
import com.keepit.search.Article
import scala.concurrent.Future

@ProvidedBy(classOf[ScrapeProcessorProvider])
trait ScrapeProcessor {
  def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def scrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]):Future[(NormalizedURI, Option[Article])]
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]): Unit
  def pull:Unit = {}
}

@Singleton
class ScrapeProcessorProvider @Inject() (
  scraperConfig: ScraperConfig,
  queuedScrapeProcessor:QueuedScrapeProcessor,
  asyncScrapeProcessor:AsyncScrapeProcessor,
  syncScrapeProcessor:SyncScrapeProcessor
) extends Provider[ScrapeProcessor] with Logging {

  lazy val processor = if (scraperConfig.queued) queuedScrapeProcessor else if (scraperConfig.async) asyncScrapeProcessor else syncScrapeProcessor // config-based toggle
  log.info(s"[ScrapeProcessorProvider] created with config:$scraperConfig proc:$processor")

  def get = processor
}



