package com.keepit.rover.fetcher

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.rover.fetcher.apache.{ ApacheHttpFetcher, HttpFetchEnforcerConfig }
import com.keepit.scraper.ScraperSchedulerConfig
import net.codingwell.scalaguice.ScalaModule

trait HttpFetcherModule extends ScalaModule

case class ProdHttpFetcherModule() extends HttpFetcherModule {
  def configure(): Unit = {
    bind[HttpFetcher].to[ApacheHttpFetcher]
  }

  @Singleton
  @Provides
  def httpFetcher(airbrake: AirbrakeNotifier, schedulingProperties: SchedulingProperties, enforcerConfig: HttpFetchEnforcerConfig, scheduler: ScraperSchedulerConfig): ApacheHttpFetcher = {
    new ApacheHttpFetcher(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 1000,
      soTimeOut = 1000,
      schedulingProperties,
      enforcerConfig
    )
  }
}

case class DevHttpFetcherModule() extends HttpFetcherModule {
  def configure(): Unit = {
    bind[HttpFetcher].to[ApacheHttpFetcher]
  }

  @Singleton
  @Provides
  def httpFetcher(airbrake: AirbrakeNotifier, schedulingProperties: SchedulingProperties, enforcerConfig: HttpFetchEnforcerConfig): ApacheHttpFetcher = {
    new ApacheHttpFetcher(
      airbrake,
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 5 * 1000,
      soTimeOut = 5 * 1000,
      schedulingProperties,
      enforcerConfig
    )
  }

}