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

//  @Singleton
//  @Provides
//  def httpFetcher(airbrake:AirbrakeNotifier): HttpFetcher = {
//    new HttpFetcherImpl(
//      airbrake,
//      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
//      connectionTimeout = 30000,
//      soTimeOut = 30000,
//      trustBlindly = true
//    )
//  }
}

case class DevScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig().copy(scrapePendingFrequency = 5, pendingOverdueThreshold = 5, pendingSkipThreshold = 50)

//  @Singleton
//  @Provides
//  def httpFetcher(airbrake:AirbrakeNotifier): HttpFetcher = {
//    new HttpFetcherImpl(
//      airbrake,
//      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
//      connectionTimeout = 3000,
//      soTimeOut = 3000,
//      trustBlindly = true
//    )
//  }
}

