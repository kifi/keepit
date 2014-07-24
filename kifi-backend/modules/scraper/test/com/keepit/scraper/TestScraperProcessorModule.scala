package com.keepit.scraper

import com.keepit.model.{ HttpProxy, PageInfo, ScrapeInfo, NormalizedURI }
import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.extractor.{ ExtractorProviderType, ExtractorFactoryImpl, ExtractorFactory }
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.scraper.fetcher.apache.ApacheHttpFetcher

import scala.concurrent.Future

case class TestScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[ShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[SyncShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    install(TestScraperConfigModule())
    install(TestScrapeSchedulerConfigModule())
  }

  @Singleton
  @Provides
  def scrapeProcessor: ScrapeProcessor = new ScrapeProcessor {
    def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = Future.successful(None)
    def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit = ()
  }

}
