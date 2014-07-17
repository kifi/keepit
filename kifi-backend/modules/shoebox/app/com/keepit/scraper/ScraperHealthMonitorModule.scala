package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.{ Play, Configuration }

trait ScraperHealthMonitorModule extends ScalaModule

case class ProdScraperHealthMonitorModule() extends ScraperHealthMonitorModule {

  def configure {
    bind[ScraperHealthMonitorPlugin].to[ScraperHealthMonitorPluginImpl].in[AppScoped]
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }

}

case class DevScraperHealthMonitorModule() extends ScraperHealthMonitorModule {

  def configure {
    bind[ScraperHealthMonitorPlugin].to[ScraperHealthMonitorPluginImpl].in[AppScoped]
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }
}
