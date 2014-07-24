package com.keepit.scraper

import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.fetcher.{ ProdHttpFetcherModule, HttpFetcher }
import com.keepit.scraper.fetcher.apache.ApacheHttpFetcher
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import com.keepit.scraper.extractor.{ ExtractorFactoryImpl, ExtractorFactory }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties

trait ScrapeProcessorModule extends ScalaModule

case class ProdScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[ShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[SyncShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    bind[ScrapeProcessor].to[QueuedScrapeProcessor]
    install(ProdScraperConfigModule())
    install(ProdScrapeSchedulerConfigModule())
  }
}

