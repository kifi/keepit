package com.keepit.scraper

import com.keepit.rover.fetcher.ScraperHttpConfig
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import play.api.{ Play, Configuration }

trait ScraperConfigModule extends ScalaModule {

  protected def conf: Configuration

  @Singleton
  @Provides
  def scraperQueueConfig: ScraperQueueConfig = {
    ScraperQueueConfig(
      terminateThreshold = conf.getInt("scraper.queue.terminateThreshold").get,
      queueSizeThreshold = conf.getInt("scraper.queue.sizeThreshold").get,
      pullThreshold = conf.getInt("scraper.queue.pullThreshold"),
      terminatorFreq = conf.getInt("scraper.queue.terminatorFreq").get
    )
  }

  @Singleton
  @Provides
  def scraperHttpConfig: ScraperHttpConfig = {
    ScraperHttpConfig(
      httpFetcherEnforcerFreq = conf.getInt("scraper.http.fetcherEnforcerFreq").get,
      httpFetcherQSizeThreshold = conf.getInt("scraper.http.fetcherQSizeThreshold").get
    )

  }

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig = {
    ScraperConfig(
      changeThreshold = conf.getInt("scraper.changeThreshold").get,
      pullFrequency = conf.getInt("scraper.pullFrequency").get, // seconds
      queued = conf.getBoolean("scraper.queued").get,
      async = conf.getBoolean("scraper.async").get,
      syncAwaitTimeout = conf.getInt("scraper.syncAwaitTimeout").get,
      serviceCallTimeout = conf.getInt("scraper.serviceCallTimeout").get,
      batchSize = conf.getInt("scraper.batchSize").get,
      batchMax = conf.getInt("scraper.batchMax").get,
      httpConfig = httpConfig,
      queueConfig = queueConfig
    )
  }
}

case class ProdScraperConfigModule() extends ScraperConfigModule {

  def configure() {
    install(ProdScrapeSchedulerConfigModule())
  }

  override protected def conf: Configuration = Play.current.configuration
}
