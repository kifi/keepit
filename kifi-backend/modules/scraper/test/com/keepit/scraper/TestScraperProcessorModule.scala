package com.keepit.scraper

import com.keepit.scraper.extractor.{ ExtractorFactoryImpl, ExtractorFactory }
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.scraper.fetcher.apache.ApacheHttpFetcher

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
  def httpFetcher(airbrake: AirbrakeNotifier, schedulingProperties: SchedulingProperties, scraperConfig: ScraperConfig): HttpFetcher = {
    new ApacheHttpFetcher(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 5 * 1000,
      soTimeOut = 5 * 1000,
      trustBlindly = true,
      schedulingProperties,
      scraperConfig.httpConfig
    )
  }
}
