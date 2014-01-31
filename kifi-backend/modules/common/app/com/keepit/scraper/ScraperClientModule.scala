package com.keepit.scraper

import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{HttpClientImpl, HttpClient}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.{ServiceClient, ServiceType}
import play.api.Play._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import akka.actor.Scheduler

trait ScraperServiceClientModule extends ScalaModule

case class ProdScraperServiceClientModule() extends ScraperServiceClientModule {
  def configure() {
  }

  @Singleton
  @Provides
  def ScraperServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    serviceClientBinder: ScalaMultibinder[ServiceClient],
    airbrakeNotifier: AirbrakeNotifier): ScraperServiceClient = {
    val scraper = new ScraperServiceClientImpl(
      airbrakeNotifier,
      client,
      serviceDiscovery.serviceCluster(ServiceType.SCRAPER)
    )
    serviceClientBinder.addBinding().toInstance(scraper)
    scraper
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
