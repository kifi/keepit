package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.{Play, Configuration}

trait ScraperConfigModule extends ScalaModule {

  protected def conf: Configuration

  @Singleton
  @Provides
  def scraperIntervalConfig: ScraperIntervalConfig = {
    ScraperIntervalConfig(
      minInterval = 24.0d, //hours
      maxInterval = 1024.0d, //hours
      intervalIncrement = 6.0d, //hours
      intervalDecrement = 2.0d //hours
    )
  }

  @Singleton
  @Provides
  def scraperQueueConfig: ScraperQueueConfig = {
    ScraperQueueConfig(
      terminateThreshold = conf.getInt("scraper.terminate.threshold").getOrElse(2 * 1000 * 60),
      queueSizeThreshold = conf.getInt("scraper.queue.size.threshold").getOrElse(100),
      pullThreshold = conf.getInt("scraper.pull.threshold"),
      terminatorFreq = conf.getInt("scraper.terminator.freq").getOrElse(5)
    )
  }

  @Singleton
  @Provides
  def scraperHttpConfig: ScraperHttpConfig = {
    ScraperHttpConfig(
      httpFetcherEnforcerFreq = conf.getInt("scraper.fetcher.enforcer.freq").getOrElse(5),
      httpFetcherQSizeThreshold = conf.getInt("fetcher.queue.size.threshold").getOrElse(100)
    )

  }

  protected def defaultScraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig = {
    ScraperConfig(
      intervalConfig = intervalConfig,
      initialBackoff = 3.0d, //hours
      maxBackoff = 1024.0d, //hours
      maxRandomDelay = 600, // seconds
      changeThreshold = 0.5,
      pullMultiplier = conf.getInt("scraper.pull.multiplier").getOrElse(8),
      pullFrequency = conf.getInt("scraper.pull.freq").getOrElse(5), // seconds
      scrapePendingFrequency = conf.getInt("scraper.pending.freq").getOrElse(30), // seconds
      queued = conf.getBoolean("scraper.plugin.queued").getOrElse(true),
      async = conf.getBoolean("scraper.plugin.async").getOrElse(false),
      actorTimeout = conf.getInt("scraper.actor.timeout").getOrElse(20000),
      syncAwaitTimeout = conf.getInt("scraper.plugin.sync.await.timeout").getOrElse(20000),
      serviceCallTimeout = conf.getInt("scraper.service.call.timeout").getOrElse(20000),
      batchSize = conf.getInt("scraper.service.batch.size").getOrElse(10),
      batchMax = conf.getInt("scraper.service.batch.max").getOrElse(50),
      pendingOverdueThreshold = conf.getInt("scraper.service.pending.overdue.threshold").getOrElse(20), // minutes
      checkOverdueCountFrequency = conf.getInt("scraper.overdue.check.freq").getOrElse(20), // minutes
      overdueCountThreshold = conf.getInt("scraper.overdue.count.threshold").getOrElse(1000),
      httpConfig = httpConfig,
      queueConfig = queueConfig
    )
  }
}

case class ProdScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  override protected def conf: Configuration = Play.current.configuration

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig)
}

case class DevScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  override protected def conf: Configuration = Play.current.configuration

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig).copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 10)
}

case class TestScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  override protected def conf: Configuration = Configuration.empty

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig).copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 10)
}
