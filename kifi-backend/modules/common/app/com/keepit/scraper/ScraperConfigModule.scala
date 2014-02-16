package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.config.GlobalConfig._

trait ScraperConfigModule extends ScalaModule {

  @Singleton
  @Provides
  def scraperQueueConfig: ScraperQueueConfig = {
    ScraperQueueConfig(
      terminateThreshold = safeConfig.getInt("scraper.terminate.threshold").getOrElse(2 * 1000 * 60),
      queueSizeThreshold = safeConfig.getInt("scraper.queue.size.threshold").getOrElse(100),
      pullThreshold = safeConfig.getInt("scraper.pull.threshold"),
      terminatorFreq = safeConfig.getInt("scraper.terminator.freq").getOrElse(5)
    )
  }

  @Singleton
  @Provides
  def scraperHttpConfig: ScraperHttpConfig = {
    ScraperHttpConfig(
      httpFetcherEnforcerFreq = safeConfig.getInt("scraper.fetcher.enforcer.freq").getOrElse(5),
      httpFetcherQSizeThreshold = safeConfig.getInt("fetcher.queue.size.threshold").getOrElse(100)
    )
  }

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

  protected def defaultScraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig = {
    ScraperConfig(
      intervalConfig = intervalConfig,
      initialBackoff = 3.0d, //hours
      maxBackoff = 1024.0d, //hours
      maxRandomDelay = 600, // seconds
      changeThreshold = 0.5,
      pullMultiplier = safeConfig.getInt("scraper.pull.multiplier").getOrElse(8),
      pullFrequency = safeConfig.getInt("scraper.pull.freq").getOrElse(5), // seconds
      scrapePendingFrequency = safeConfig.getInt("scraper.pending.freq").getOrElse(30), // seconds
      queued = safeConfig.getBoolean("scraper.plugin.queued").getOrElse(true),
      async = safeConfig.getBoolean("scraper.plugin.async").getOrElse(false),
      actorTimeout = safeConfig.getInt("scraper.actor.timeout").getOrElse(20000),
      syncAwaitTimeout = safeConfig.getInt("scraper.plugin.sync.await.timeout").getOrElse(20000),
      serviceCallTimeout = safeConfig.getInt("scraper.service.call.timeout").getOrElse(20000),
      batchSize = safeConfig.getInt("scraper.service.batch.size").getOrElse(10),
      batchMax = safeConfig.getInt("scraper.service.batch.max").getOrElse(50),
      pendingOverdueThreshold = safeConfig.getInt("scraper.service.pending.overdue.threshold").getOrElse(20), // minutes
      checkOverdueCountFrequency = safeConfig.getInt("scraper.overdue.check.freq").getOrElse(20), // minutes
      overdueCountThreshold = safeConfig.getInt("scraper.overdue.count.threshold").getOrElse(1000),
      httpConfig = httpConfig,
      queueConfig = queueConfig
    )
  }
}

case class ProdScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig)
}

case class DevScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig).copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 10)
}

case class TestScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  @Singleton
  @Provides
  def scraperConfig(queueConfig: ScraperQueueConfig, httpConfig: ScraperHttpConfig, intervalConfig: ScraperIntervalConfig): ScraperConfig =
    defaultScraperConfig(queueConfig, httpConfig, intervalConfig).copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 10)
}
