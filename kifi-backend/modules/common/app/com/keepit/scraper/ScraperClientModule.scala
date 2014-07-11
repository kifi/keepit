package com.keepit.scraper

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClientImpl, HttpClient }
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import akka.actor.Scheduler

trait ScraperServiceClientModule extends ScalaModule

case class ProdScraperServiceClientModule() extends ScraperServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def ScraperServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: ScraperCacheProvider): ScraperServiceClient = {
    new ScraperServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.SCRAPER),
      cacheProvider
    )
  }

}

case class TestScraperServiceClientModule() extends ScraperServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def ScraperServiceClient(airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler): ScraperServiceClient = {
    new FakeScraperServiceClientImpl(airbrakeNotifier, scheduler)
  }

}
