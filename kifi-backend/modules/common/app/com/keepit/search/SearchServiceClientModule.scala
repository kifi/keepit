package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import play.api.Play._

trait SearchServiceClientModule extends ScalaModule

case class ProdSearchServiceClientModule() extends SearchServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def searchServiceClient(
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): SearchServiceClient = {
    new SearchServiceClientImpl(
      serviceDiscovery.serviceCluster(ServiceType.SEARCH),
      client,
      airbrakeNotifier
    )
  }

}
