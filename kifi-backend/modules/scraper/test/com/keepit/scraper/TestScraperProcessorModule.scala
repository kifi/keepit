package com.keepit.scraper

import com.google.inject.{ Provides, Singleton }
import com.keepit.inject.AppScoped
import com.keepit.model.{ HttpProxy, NormalizedURI, ScrapeInfo }
import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.extractor.{ ExtractorFactory, ExtractorFactoryImpl, ExtractorProviderType }

import scala.concurrent.Future

case class TestScraperProcessorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    install(FakeScraperConfigModule())
  }

  @Singleton
  @Provides
  def scrapeProcessor: ScrapeProcessor = new ScrapeProcessor {
    def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = Future.successful(None)
    def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, proxyOpt: Option[HttpProxy]): Unit = ()
  }

}

case class FakeScraperProcessorActorModule() extends ScrapeProcessorModule {

  def configure {
    bind[ExtractorFactory].to[ExtractorFactoryImpl].in[AppScoped]
    bind[PullerPlugin].to[PullerPluginImpl].in[AppScoped]
    bind[ScrapeProcessor].to[ScrapeProcessorActorImpl]
    install(FakeScraperConfigModule())
    install(FakeScrapeSchedulerConfigModule())
  }

}
