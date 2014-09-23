package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceType

trait DistributedSearchServiceClientModule extends ScalaModule

case class ProdDistributedSearchServiceClientModule() extends DistributedSearchServiceClientModule {

  def configure() {}

  @Provides @Singleton
  def distributedSearchServiceClient(
    searchClient: SearchServiceClient,
    client: HttpClient,
    serviceDiscovery: ServiceDiscovery,
    airbrakeNotifier: AirbrakeNotifier): DistributedSearchServiceClient = {
    new DistributedSearchServiceClientImpl(
      searchClient,
      serviceDiscovery.serviceCluster(ServiceType.SEARCH),
      client,
      airbrakeNotifier
    )
  }
}
