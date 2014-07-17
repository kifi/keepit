package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait ScraperHealthMonitorModule extends ScalaModule

case class ProdScraperHealthMonitorModule() extends ScraperHealthMonitorModule {

  def configure {
    bind[ScraperHealthMonitorPlugin].to[ScraperHealthMonitorPluginImpl].in[AppScoped]
  }

}

case class DevScraperHealthMonitorModule() extends ScraperHealthMonitorModule {

  def configure {
    bind[ScraperHealthMonitorPlugin].to[ScraperHealthMonitorPluginImpl].in[AppScoped]
  }
}
