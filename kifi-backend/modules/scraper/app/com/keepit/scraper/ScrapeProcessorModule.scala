package com.keepit.scraper

import com.keepit.inject.AppScoped
import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.extractor.{ ExtractorFactory, ExtractorFactoryImpl }
import net.codingwell.scalaguice.ScalaModule

trait ScrapeProcessorModule extends ScalaModule

case class ProdScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[ShoeboxDbCallbacks].to[ShoeboxDbCallbackHelper].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    bind[ScrapeProcessor].to[ScrapeProcessorActorImpl]
    install(ProdScraperConfigModule())
    install(ProdScrapeSchedulerConfigModule())
  }
}

