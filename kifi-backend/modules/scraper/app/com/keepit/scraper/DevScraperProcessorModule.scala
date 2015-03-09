package com.keepit.scraper

import com.keepit.inject.AppScoped
import com.google.inject.{ Provider, Provides, Singleton }
import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.extractor.{ ExtractorFactoryImpl, ExtractorFactory }
import akka.actor.ActorSystem
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.scraper.fetcher.{ DevHttpFetcherModule, DeprecatedHttpFetcher }
import com.keepit.scraper.fetcher.apache.ApacheHttpFetcher

case class DevScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    bind[ScrapeProcessor].to[ScrapeProcessorActorImpl]
    install(ProdScraperConfigModule())
  }

}
