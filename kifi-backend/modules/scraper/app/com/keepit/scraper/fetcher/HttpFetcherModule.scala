package com.keepit.scraper.fetcher

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.scraper.{ ScraperSchedulerConfig, ScraperConfig }
import com.keepit.scraper.fetcher.apache.ApacheHttpFetcher
import net.codingwell.scalaguice.ScalaModule

trait HttpFetcherModule extends ScalaModule

case class ProdHttpFetcherModule() extends HttpFetcherModule {
  def configure(): Unit = {
  }

  @Singleton
  @Provides
  def httpFetcher(airbrake: AirbrakeNotifier, schedulingProperties: SchedulingProperties, scraperConfig: ScraperConfig, scheduler: ScraperSchedulerConfig): DeprecatedHttpFetcher = {
    new ApacheHttpFetcher(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = scheduler.scrapePendingFrequency * 1000,
      soTimeOut = scheduler.scrapePendingFrequency * 1000,
      schedulingProperties,
      scraperConfig.httpConfig
    )
  }
}

case class DevHttpFetcherModule() extends HttpFetcherModule {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def httpFetcher(airbrake: AirbrakeNotifier, schedulingProperties: SchedulingProperties, scraperConfig: ScraperConfig): DeprecatedHttpFetcher = {
    new ApacheHttpFetcher(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 5 * 1000,
      soTimeOut = 5 * 1000,
      schedulingProperties,
      scraperConfig.httpConfig
    )
  }

}