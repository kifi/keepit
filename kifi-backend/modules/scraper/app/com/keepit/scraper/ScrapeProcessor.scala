package com.keepit.scraper

import com.google.inject.{Inject, ImplementedBy}
import play.api.Plugin
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ScrapeInfo, NormalizedURI}
import com.keepit.scraper.extractor.ExtractorFactory
import com.keepit.search.{Article, ArticleStore}
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.shoebox.ShoeboxServiceClient

@ImplementedBy(classOf[ScrapeProcessorPluginImpl])
trait ScrapeProcessorPlugin extends Plugin {
  def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, proc:(NormalizedURI, ScrapeInfo) => (NormalizedURI, Option[Article])): Unit
}

class ScrapeProcessorPluginImpl @Inject() (actor:ActorInstance[ScrapeProcessor]) extends ScrapeProcessorPlugin with Logging {
  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, proc:(NormalizedURI, ScrapeInfo) => (NormalizedURI, Option[Article])): Unit = {
    actor.ref ! AsyncScrape(uri, info, proc)
  }
}

case class AsyncScrape(uri:NormalizedURI, info:ScrapeInfo, proc:(NormalizedURI, ScrapeInfo) => (NormalizedURI, Option[Article]))

class ScrapeProcessor @Inject() (
  airbrake:AirbrakeNotifier,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  scraperConfig: ScraperConfig,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  shoeboxServiceClient: ShoeboxServiceClient
) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case AsyncScrape(uri, info, proc) => {
      log.info(s"[AsyncScrape] begin scraping ${uri.url}")
      val ts = System.currentTimeMillis
      val res = proc(uri, info)
      log.info(s"[AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1}")
    }
  }

}