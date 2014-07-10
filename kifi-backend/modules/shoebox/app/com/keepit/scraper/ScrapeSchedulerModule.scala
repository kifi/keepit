package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.{ Play, Configuration }

trait ScrapeSchedulerModule extends ScalaModule

case class ProdScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }

}

case class DevScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }
}
