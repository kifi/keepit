package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.{Play, Configuration}

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
      pullMultiplier = conf.getInt("scraper.pullMultiplier").get,
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

case class TestScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  override protected def conf: Configuration = new Configuration(Configuration.empty.underlying) {
    override def getInt(key: String): Option[Int] = Some(0)
    override def getDouble(key: String): Option[Double] = Some(0)
    override def getBoolean(key: String): Option[Boolean] = Some(true)
  }
}
