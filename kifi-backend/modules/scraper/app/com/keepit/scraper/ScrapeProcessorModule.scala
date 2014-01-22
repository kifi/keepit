package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{Provider, Provides, Singleton}
import com.keepit.scraper.extractor.{ExtractorFactoryImpl, ExtractorFactory}
import akka.actor.ActorSystem
import com.keepit.common.healthcheck.AirbrakeNotifier

trait ScrapeProcessorModule extends ScalaModule

case class ProdScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[ShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[SyncShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[AsyncScrapeProcessor].to[SimpleAsyncScrapeProcessor].in[AppScoped]
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig()

  @Singleton
  @Provides
  def httpFetcher(airbrake:AirbrakeNotifier): HttpFetcher = {
    new HttpFetcherImpl(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = scraperConfig.scrapePendingFrequency * 1000, // todo: revisit
      soTimeOut = scraperConfig.scrapePendingFrequency * 1000,
      trustBlindly = true
    )
  }

  @Singleton
  @Provides
  def syncScrapeProcessor(sysProvider: Provider[ActorSystem], procProvider: Provider[SyncScraperActor]):SyncScrapeProcessor = {
    new SyncScrapeProcessor(scraperConfig, sysProvider, procProvider, Runtime.getRuntime.availableProcessors * 128)
  }
}


