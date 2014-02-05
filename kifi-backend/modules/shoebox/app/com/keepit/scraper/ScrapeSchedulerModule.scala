package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier

trait ScrapeSchedulerModule extends ScalaModule

case class ProdScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig()
}

case class DevScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig().copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 10, pendingSkipThreshold = 1000)
}

